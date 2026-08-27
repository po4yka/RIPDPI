package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import com.poyka.ripdpi.data.subscription.ClashSubscriptionParser
import com.poyka.ripdpi.data.subscription.MaxSubscriptionProfiles
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.subscription.SubscriptionPayloadTooLargeException
import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import com.poyka.ripdpi.data.subscription.fitsSubscriptionLimits
import com.poyka.ripdpi.data.subscription.isValidForRefresh
import com.poyka.ripdpi.data.subscription.readBoundedSubscriptionPayload
import com.poyka.ripdpi.data.subscription.toActivationRequest
import com.poyka.ripdpi.data.subscription.toSelectorFailover
import com.poyka.ripdpi.data.subscription.withUserinfoHeader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal enum class SubscriptionRefreshRunResult {
    SUCCESS,
    RETRY,
}

/** Result of one manual or automatic subscription refresh. */
sealed interface SubscriptionRefreshResult {
    data class Updated(
        val memberCount: Int,
    ) : SubscriptionRefreshResult

    data class Failed(
        val failure: SubscriptionRefreshFailure,
        val retryable: Boolean,
    ) : SubscriptionRefreshResult
}

internal fun interface SubscriptionSignalPublisher {
    fun publish(
        groups: List<ProxyGroup>,
        nowEpochMillis: Long,
    )
}

@Singleton
class SubscriptionRefreshCoordinator private constructor(
    private val repository: ProxyGroupRepository,
    private val awgProfileRepository: AwgProfileRepository,
    private val signalPublisher: SubscriptionSignalPublisher,
    private val httpClient: OkHttpClient,
    private val clockMillis: () -> Long,
) {
    @Inject
    internal constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionStatusNotifier,
        expiryClock: SubscriptionExpiryClock,
    ) : this(
        repository = repository,
        awgProfileRepository = awgProfileRepository,
        signalPublisher = signalPublisher,
        httpClient = OkHttpClient(),
        clockMillis = expiryClock::nowMillis,
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
    private val deliveryHttpClient =
        httpClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(EndpointTimeoutSeconds, TimeUnit.SECONDS)
            .build()

    internal suspend fun refreshAll(): SubscriptionRefreshRunResult {
        val allGroups = repository.list()
        val due = subscriptionsDueForAutoUpdate(allGroups, clockMillis())
        if (due.isEmpty()) {
            signalPublisher.publish(allGroups, clockMillis())
            return SubscriptionRefreshRunResult.SUCCESS
        }
        var retry = false
        for (group in due) {
            when (val result = refreshGroup(group)) {
                is SubscriptionRefreshResult.Updated -> Unit
                is SubscriptionRefreshResult.Failed -> retry = retry || result.retryable
            }
        }
        signalPublisher.publish(repository.list(), clockMillis())
        return if (retry) SubscriptionRefreshRunResult.RETRY else SubscriptionRefreshRunResult.SUCCESS
    }

    /** Refreshes one group through the same persistence path used by WorkManager. */
    suspend fun refresh(groupId: String): SubscriptionRefreshResult {
        val group =
            repository.list().firstOrNull { it.id == groupId }
                ?: return SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.UNREACHABLE, retryable = false)
        val result = refreshGroup(group)
        signalPublisher.publish(repository.list(), clockMillis())
        return result
    }

    private suspend fun refreshGroup(group: ProxyGroup): SubscriptionRefreshResult {
        val attemptedAt = clockMillis()
        val subscription =
            group.subscription
                ?: return SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.UNREACHABLE, retryable = false)
        val priorFailure = subscription.lastRefreshFailure
        return if (subscription.kind == SubscriptionKind.BOOTSTRAP) {
            // Only BootstrapConsumer may consume this URL; refresh never replays single-use delivery.
            SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.INVALIDATED, retryable = false)
        } else if (priorFailure?.isTerminal == true) {
            SubscriptionRefreshResult.Failed(priorFailure, retryable = false)
        } else {
            val outcome =
                when {
                    subscription.tokenExpiresAtEpochMillis?.let { attemptedAt >= it } == true -> {
                        RefreshOutcome.Failure(
                            failure = SubscriptionRefreshFailure.EXPIRED,
                            lifecycleState = SubscriptionLifecycleState.EXPIRED,
                            retry = false,
                        )
                    }

                    subscription.link.isBlank() -> {
                        RefreshOutcome.Failure(
                            failure = SubscriptionRefreshFailure.UNREACHABLE,
                            lifecycleState = null,
                            retry = false,
                        )
                    }

                    else -> {
                        fetchMirrors(group, subscription)
                    }
                }
            when (outcome) {
                is RefreshOutcome.Updated -> {
                    persistSuccess(group.id, attemptedAt, outcome)
                    SubscriptionRefreshResult.Updated(outcome.profiles.size)
                }

                is RefreshOutcome.Failure -> {
                    persistFailure(group.id, attemptedAt, outcome)
                    SubscriptionRefreshResult.Failed(outcome.failure, outcome.retry)
                }
            }
        }
    }

    private suspend fun persistSuccess(
        groupId: String,
        attemptedAt: Long,
        outcome: RefreshOutcome.Updated,
    ) {
        val updated =
            repository.updateGroup(groupId) { current ->
                val subscription = current.subscription ?: return@updateGroup current
                val withMembers =
                    if (outcome.clearRelayMembers) {
                        current.copy(members = emptyList(), cloudflareMemberIds = emptySet())
                    } else {
                        SubscriptionMemberPersistence.apply(
                            group = current,
                            members = outcome.profiles,
                            failover = outcome.failover,
                            cloudflareMemberIds = outcome.cloudflareMemberIds,
                            isSelector = outcome.isSelector,
                        )
                    }
                withMembers.copy(
                    packageRoutingRules = outcome.packageRoutingRules,
                    subscription =
                        subscription
                            .withUserinfoHeader(outcome.subscriptionUserinfo)
                            .copy(
                                lastUpdated = attemptedAt,
                                lastRefreshAttemptAtEpochMillis = attemptedAt,
                                tokenExpiresAtEpochMillis = outcome.tokenExpiresAtEpochMillis,
                                lifecycleState = SubscriptionLifecycleState.ACTIVE,
                                lastRefreshFailure = null,
                                mirrors = outcome.subscriptionMirrors ?: subscription.mirrors,
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
        group: ProxyGroup,
        url: String,
        customUserAgent: String,
        token: String,
    ): RefreshOutcome =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .apply {
                        if (customUserAgent.isNotBlank()) header("User-Agent", customUserAgent)
                        if (token.isNotBlank()) header("Authorization", "Bearer $token")
                    }.build()
            try {
                deliveryHttpClient.newCall(request).execute().use { response ->
                    classifyHttpFailure(response.code, group)?.let { return@use it }
                    val body =
                        try {
                            response.body.readBoundedSubscriptionPayload()
                        } catch (_: SubscriptionPayloadTooLargeException) {
                            return@use payloadTooLargeFailure()
                        }
                    val singBox = SingBoxSubscriptionParser.parse(body, group.id) as? SingBoxParseResult.Success
                    if (singBox?.tokenExpiresAtEpochMillis?.let { clockMillis() >= it } == true) {
                        return@use RefreshOutcome.Failure(
                            SubscriptionRefreshFailure.EXPIRED,
                            SubscriptionLifecycleState.EXPIRED,
                            false,
                        )
                    }
                    val wireGuard =
                        if (WireGuardIniSubscriptionParser.looksLikeWireGuardIni(body)) {
                            WireGuardIniSubscriptionParser.parse(body, group.id)
                        } else {
                            null
                        }
                    val wireGuardCount =
                        singBox.orEmptyAwgCount() +
                            wireGuard?.let { it.amneziaWgProfiles.size + it.profiles.size }.orZero()
                    val profiles = parseProfiles(body, group.id, wireGuardCount, singBox)
                    if (profiles != null) {
                        if (!profiles.profiles.fitsSubscriptionLimits() || wireGuardCount > MaxSubscriptionProfiles) {
                            payloadTooLargeFailure()
                        } else {
                            persistWireGuardProfiles(singBox, wireGuard)
                            profiles.copy(
                                subscriptionUserinfo = response.header(SubscriptionUserinfoHeader).orEmpty(),
                                subscriptionMirrors = singBox?.subscriptionMirrors,
                            )
                        }
                    } else {
                        RefreshOutcome.Failure(
                            failure = SubscriptionRefreshFailure.PARSE_ERROR,
                            lifecycleState = null,
                            retry = false,
                        )
                    }
                }
            } catch (_: IOException) {
                RefreshOutcome.Failure(
                    failure = SubscriptionRefreshFailure.UNREACHABLE,
                    lifecycleState = null,
                    retry = true,
                )
            }
        }

    private fun classifyHttpFailure(
        code: Int,
        group: ProxyGroup,
    ): RefreshOutcome.Failure? =
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
                val expiry = group.subscription?.tokenExpiresAtEpochMillis
                if (expiry != null && clockMillis() >= expiry) {
                    RefreshOutcome.Failure(
                        SubscriptionRefreshFailure.EXPIRED,
                        SubscriptionLifecycleState.EXPIRED,
                        false,
                    )
                } else {
                    RefreshOutcome.Failure(
                        SubscriptionRefreshFailure.INVALIDATED,
                        SubscriptionLifecycleState.UNAVAILABLE,
                        false,
                    )
                }
            }

            code == HttpTooManyRequests -> {
                RefreshOutcome.Failure(SubscriptionRefreshFailure.RATE_LIMITED, null, true)
            }

            code in HttpServerErrorStart..HttpServerErrorEnd -> {
                RefreshOutcome.Failure(SubscriptionRefreshFailure.SERVER_ERROR, null, true)
            }

            else -> {
                RefreshOutcome.Failure(
                    SubscriptionRefreshFailure.UNREACHABLE,
                    null,
                    false,
                )
            }
        }

    private suspend fun fetchMirrors(
        group: ProxyGroup,
        subscription: Subscription,
    ): RefreshOutcome {
        if (!subscription.mirrors.isValidForRefresh() ||
            (
                subscription.token.isNotBlank() &&
                    !SubscriptionMirrorSet(
                        listOf(SubscriptionMirror("original", subscription.link, subscription.token)),
                    ).isValidForRefresh()
            )
        ) {
            return RefreshOutcome.Failure(SubscriptionRefreshFailure.PARSE_ERROR, null, false)
        }
        return fetchValidatedMirrors(group, subscription)
    }

    private suspend fun fetchValidatedMirrors(
        group: ProxyGroup,
        subscription: Subscription,
    ): RefreshOutcome {
        val endpoints =
            subscription.mirrors
                .refreshOrder()
                .map { it.url to it.token }
                .toMutableList()
        if (endpoints.none { it.first == subscription.link }) endpoints += subscription.link to subscription.token
        var last: RefreshOutcome = RefreshOutcome.Failure(SubscriptionRefreshFailure.UNREACHABLE, null, true)
        var retry = false
        for ((url, token) in endpoints) {
            last = fetchAndParse(group, url, subscription.customUserAgent, token)
            if (last is RefreshOutcome.Updated ||
                (last is RefreshOutcome.Failure && last.failure.isTerminal)
            ) {
                return last
            }
            retry = retry || (last as RefreshOutcome.Failure).retry
        }
        return (last as RefreshOutcome.Failure).copy(retry = retry)
    }

    private suspend fun parseProfiles(
        body: String,
        groupId: String,
        wireGuardCount: Int,
        singBox: SingBoxParseResult.Success?,
    ): RefreshOutcome.Updated? {
        val selector = SelectorUrltestGroupImport.import(body, groupId)
        var parsed =
            if (selector is SelectorUrltestImportResult.Success && selector.profiles.isNotEmpty()) {
                RefreshOutcome.Updated(
                    selector.profiles,
                    selector.failoverPolicy.toSelectorFailover(),
                    cloudflareMemberIds = selector.cloudflareMemberIds,
                    isSelector = selector.group != null,
                    tokenExpiresAtEpochMillis = singBox?.tokenExpiresAtEpochMillis,
                    packageRoutingRules = singBox?.packageRoutingRules.orEmpty(),
                )
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
        if (parsed == null && wireGuardCount > 0) {
            parsed =
                RefreshOutcome.Updated(
                    emptyList(),
                    null,
                    tokenExpiresAtEpochMillis = singBox?.tokenExpiresAtEpochMillis,
                    packageRoutingRules = singBox?.packageRoutingRules.orEmpty(),
                    clearRelayMembers = true,
                )
        }
        return parsed
    }

    private suspend fun persistWireGuardProfiles(
        singBox: SingBoxParseResult.Success?,
        wireGuardIni: com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionResult?,
    ): Int {
        var saved = 0
        if (singBox != null) {
            singBox.amneziaWgProfiles.forEach { profile ->
                awgProfileRepository.save(profile.displayName, profile.toActivationRequest())
                saved++
            }
        }
        if (wireGuardIni != null) {
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

    private fun payloadTooLargeFailure(): RefreshOutcome.Failure =
        RefreshOutcome.Failure(
            failure = SubscriptionRefreshFailure.PAYLOAD_TOO_LARGE,
            lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
            retry = false,
        )

    private fun SingBoxParseResult.Success?.orEmptyAwgCount(): Int = this?.amneziaWgProfiles?.size ?: 0

    private fun Int?.orZero(): Int = this ?: 0

    private sealed interface RefreshOutcome {
        data class Updated(
            val profiles: List<ProxyProfile>,
            val failover: SelectorFailover?,
            val subscriptionUserinfo: String = "",
            val tokenExpiresAtEpochMillis: Long? = null,
            val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
            val clearRelayMembers: Boolean = false,
            val subscriptionMirrors: SubscriptionMirrorSet? = null,
            val cloudflareMemberIds: Set<String>? = null,
            val isSelector: Boolean = false,
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
        const val SubscriptionUserinfoHeader = "Subscription-Userinfo"
        const val EndpointTimeoutSeconds = 15L
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SubscriptionRefreshModule {
    @Provides
    @Singleton
    fun provideSubscriptionExpiryClock(): SubscriptionExpiryClock = SubscriptionExpiryClock(System::currentTimeMillis)
}
