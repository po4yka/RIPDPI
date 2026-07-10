package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import com.poyka.ripdpi.data.subscription.ClashSubscriptionParser
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.SubscriptionUserinfo
import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import com.poyka.ripdpi.data.subscription.toActivationRequest
import com.poyka.ripdpi.data.subscription.toSelectorFailover
import com.poyka.ripdpi.data.subscription.withUserinfoHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal enum class SubscriptionRefreshRunResult {
    SUCCESS,
    RETRY,
}

internal fun interface SubscriptionSignalPublisher {
    fun publish(
        groups: List<ProxyGroup>,
        nowEpochMillis: Long,
    )
}

@Singleton
internal class SubscriptionRefreshCoordinator private constructor(
    private val repository: ProxyGroupRepository,
    private val awgProfileRepository: AwgProfileRepository,
    private val signalPublisher: SubscriptionSignalPublisher,
    private val httpClient: OkHttpClient,
    private val clockMillis: () -> Long,
) {
    @Inject
    constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionStatusNotifier,
    ) : this(
        repository = repository,
        awgProfileRepository = awgProfileRepository,
        signalPublisher = signalPublisher,
        httpClient = OkHttpClient(),
        clockMillis = System::currentTimeMillis,
    )

    @Suppress("LongParameterList")
    internal constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionSignalPublisher,
        httpClient: OkHttpClient,
        clockMillis: () -> Long,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
    ) : this(repository, awgProfileRepository, signalPublisher, httpClient, clockMillis)

    private val log = Logger.withTag("subscription-auto-update")

    suspend fun refreshAll(): SubscriptionRefreshRunResult {
        val allGroups = repository.list()
        val due = subscriptionsDueForAutoUpdate(allGroups)
        if (due.isEmpty()) {
            signalPublisher.publish(allGroups, clockMillis())
            return SubscriptionRefreshRunResult.SUCCESS
        }
        var retry = false
        for (group in due) {
            val attemptedAt = clockMillis()
            val subscription = group.subscription ?: continue
            val outcome =
                if (subscription.link.isBlank()) {
                    RefreshOutcome.Failure(
                        failure = SubscriptionRefreshFailure.UNAVAILABLE,
                        lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
                        retry = false,
                    )
                } else {
                    fetchAndParse(
                        url = subscription.link,
                        customUserAgent = subscription.customUserAgent,
                        groupId = group.id,
                    )
                }
            when (outcome) {
                is RefreshOutcome.Updated -> {
                    persistSuccess(group.id, attemptedAt, outcome)
                }

                is RefreshOutcome.Failure -> {
                    persistFailure(group.id, attemptedAt, outcome)
                    retry = retry || outcome.retry
                }
            }
        }
        signalPublisher.publish(repository.list(), clockMillis())
        return if (retry) SubscriptionRefreshRunResult.RETRY else SubscriptionRefreshRunResult.SUCCESS
    }

    private suspend fun persistSuccess(
        groupId: String,
        attemptedAt: Long,
        outcome: RefreshOutcome.Updated,
    ) {
        val userinfo = SubscriptionUserinfo.parse(outcome.subscriptionUserinfo)
        val expired = userinfo.isExpired(attemptedAt / MillisPerSecond)
        val updated =
            repository.updateGroup(groupId) { current ->
                val subscription = current.subscription ?: return@updateGroup current
                SubscriptionMemberPersistence
                    .apply(
                        group = current,
                        members = outcome.profiles,
                        failover = outcome.failover,
                    ).copy(
                        subscription =
                            subscription
                                .withUserinfoHeader(outcome.subscriptionUserinfo)
                                .copy(
                                    lastUpdated = attemptedAt,
                                    lastRefreshAttemptAtEpochMillis = attemptedAt,
                                    lifecycleState =
                                        if (expired) {
                                            SubscriptionLifecycleState.EXPIRED
                                        } else {
                                            SubscriptionLifecycleState.ACTIVE
                                        },
                                    lastRefreshFailure =
                                        if (expired) SubscriptionRefreshFailure.EXPIRED else null,
                                ),
                    )
            }
        if (updated != null) {
            log.i { "refreshed subscription group $groupId: ${updated.members.size} profiles persisted" }
        }
    }

    private suspend fun persistFailure(
        groupId: String,
        attemptedAt: Long,
        outcome: RefreshOutcome.Failure,
    ) {
        repository.updateSubscription(groupId) { subscription ->
            subscription.copy(
                lastRefreshAttemptAtEpochMillis = attemptedAt,
                lifecycleState = outcome.lifecycleState ?: subscription.lifecycleState,
                lastRefreshFailure = outcome.failure,
            )
        }
        log.w { "subscription group $groupId refresh failed: ${outcome.failure.name}" }
    }

    private suspend fun fetchAndParse(
        url: String,
        customUserAgent: String,
        groupId: String,
    ): RefreshOutcome =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .apply {
                        if (customUserAgent.isNotBlank()) header("User-Agent", customUserAgent)
                    }.build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    classifyHttpFailure(response.code)?.let { return@use it }
                    val body = response.body.string()
                    val wireGuardCount = persistWireGuardProfiles(body, groupId)
                    val profiles = parseProfiles(body, groupId, wireGuardCount)
                    if (profiles != null) {
                        profiles.copy(subscriptionUserinfo = response.header(SubscriptionUserinfoHeader).orEmpty())
                    } else {
                        RefreshOutcome.Failure(
                            failure = SubscriptionRefreshFailure.INVALID_PAYLOAD,
                            lifecycleState = null,
                            retry = false,
                        )
                    }
                }
            } catch (_: IOException) {
                RefreshOutcome.Failure(
                    failure = SubscriptionRefreshFailure.NETWORK_ERROR,
                    lifecycleState = null,
                    retry = true,
                )
            }
        }

    private fun classifyHttpFailure(code: Int): RefreshOutcome.Failure? =
        when {
            code in HttpSuccessStart..HttpSuccessEnd -> {
                null
            }

            code == HttpForbidden -> {
                RefreshOutcome.Failure(
                    SubscriptionRefreshFailure.REVOKED,
                    SubscriptionLifecycleState.SUSPENDED,
                    false,
                )
            }

            code == HttpGone -> {
                RefreshOutcome.Failure(
                    SubscriptionRefreshFailure.EXPIRED,
                    SubscriptionLifecycleState.EXPIRED,
                    false,
                )
            }

            code == HttpTooManyRequests -> {
                RefreshOutcome.Failure(SubscriptionRefreshFailure.RATE_LIMITED, null, true)
            }

            code in HttpServerErrorStart..HttpServerErrorEnd -> {
                RefreshOutcome.Failure(SubscriptionRefreshFailure.SERVER_ERROR, null, true)
            }

            else -> {
                RefreshOutcome.Failure(
                    SubscriptionRefreshFailure.UNAVAILABLE,
                    SubscriptionLifecycleState.UNAVAILABLE,
                    false,
                )
            }
        }

    private suspend fun parseProfiles(
        body: String,
        groupId: String,
        wireGuardCount: Int,
    ): RefreshOutcome.Updated? {
        val selector = SelectorUrltestGroupImport.import(body, groupId)
        var parsed =
            if (selector is SelectorUrltestImportResult.Success && selector.profiles.isNotEmpty()) {
                RefreshOutcome.Updated(selector.profiles, selector.failoverPolicy.toSelectorFailover())
            } else {
                null
            }
        if (parsed == null && ClashSubscriptionParser.looksLikeClash(body)) {
            val clash = ClashSubscriptionParser.parse(body, groupId)
            if (clash.profiles.isNotEmpty()) parsed = RefreshOutcome.Updated(clash.profiles, null)
        }
        if (parsed == null) {
            val base64 = Base64SubscriptionParser.parse(body, groupId)
            if (base64.profiles.isNotEmpty()) parsed = RefreshOutcome.Updated(base64.profiles, null)
        }
        if (parsed == null && wireGuardCount > 0) parsed = RefreshOutcome.Updated(emptyList(), null)
        return parsed
    }

    private suspend fun persistWireGuardProfiles(
        body: String,
        groupId: String,
    ): Int {
        var saved = 0
        val singBox = SingBoxSubscriptionParser.parse(body, groupId)
        if (singBox is SingBoxParseResult.Success) {
            singBox.amneziaWgProfiles.forEach { profile ->
                awgProfileRepository.save(profile.displayName, profile.toActivationRequest())
                saved++
            }
        }
        if (WireGuardIniSubscriptionParser.looksLikeWireGuardIni(body)) {
            val wireGuardIni = WireGuardIniSubscriptionParser.parse(body, groupId)
            wireGuardIni.amneziaWgProfiles.forEach { profile ->
                awgProfileRepository.save(profile.displayName, profile.toActivationRequest())
                saved++
            }
            wireGuardIni.profiles.forEach { profile ->
                awgProfileRepository.save(profile.displayName, profile.toActivationRequest())
                saved++
            }
        }
        return saved
    }

    private sealed interface RefreshOutcome {
        data class Updated(
            val profiles: List<ProxyProfile>,
            val failover: SelectorFailover?,
            val subscriptionUserinfo: String = "",
        ) : RefreshOutcome

        data class Failure(
            val failure: SubscriptionRefreshFailure,
            val lifecycleState: SubscriptionLifecycleState?,
            val retry: Boolean,
        ) : RefreshOutcome
    }

    private companion object {
        const val HttpSuccessStart = 200
        const val HttpSuccessEnd = 299
        const val HttpForbidden = 403
        const val HttpGone = 410
        const val HttpTooManyRequests = 429
        const val HttpServerErrorStart = 500
        const val HttpServerErrorEnd = 599
        const val MillisPerSecond = 1_000L
        const val SubscriptionUserinfoHeader = "Subscription-Userinfo"
    }
}
