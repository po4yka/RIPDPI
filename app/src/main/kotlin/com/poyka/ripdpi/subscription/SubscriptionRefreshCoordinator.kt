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
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.awg.requireValidSubscriptionProfiles
import com.poyka.ripdpi.data.awg.toAwgSubscriptionProfile
import com.poyka.ripdpi.data.awg.toMigrationTarget
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.subscription.Base64SubscriptionParser
import com.poyka.ripdpi.data.subscription.ClashSubscriptionParser
import com.poyka.ripdpi.data.subscription.MaxSubscriptionProfiles
import com.poyka.ripdpi.data.subscription.MigrationMetadata
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
import com.poyka.ripdpi.data.subscription.receivedMigration
import com.poyka.ripdpi.data.subscription.toSelectorFailover
import com.poyka.ripdpi.data.subscription.withUserinfoHeader
import com.poyka.ripdpi.proxyimport.subscriptionHost
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal enum class SubscriptionRefreshRunResult {
    SUCCESS,
    RETRY,
}

/** Result of one manual or automatic subscription refresh. */
sealed interface SubscriptionRefreshResult {
    /** The fetched generation lost ownership before it could be installed. */
    data object Superseded : SubscriptionRefreshResult

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
                is SubscriptionRefreshResult.Updated, SubscriptionRefreshResult.Superseded -> Unit
                is SubscriptionRefreshResult.Failed -> retry = retry || result.retryable
            }
        }
        signalPublisher.publish(repository.list(), clockMillis())
        return if (retry) SubscriptionRefreshRunResult.RETRY else SubscriptionRefreshRunResult.SUCCESS
    }

    /** Explicit user recheck; cached terminal state cannot prove that the server still rejects renewal. */
    suspend fun refresh(groupId: String): SubscriptionRefreshResult {
        val group =
            repository.list().firstOrNull { it.id == groupId }
                ?: return SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.UNREACHABLE, retryable = false)
        val result = refreshGroup(group, manual = true)
        signalPublisher.publish(repository.list(), clockMillis())
        return result
    }

    private suspend fun refreshGroup(
        group: ProxyGroup,
        manual: Boolean = false,
    ): SubscriptionRefreshResult {
        val attemptedAt = clockMillis()
        val subscription =
            group.subscription
                ?: return SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.UNREACHABLE, retryable = false)
        val priorFailure = subscription.lastRefreshFailure
        return if (subscription.kind == SubscriptionKind.BOOTSTRAP) {
            // Only BootstrapConsumer may consume this URL; refresh never replays single-use delivery.
            SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.INVALIDATED, retryable = false)
        } else if (!manual && priorFailure?.isTerminal == true) {
            SubscriptionRefreshResult.Failed(priorFailure, retryable = false)
        } else {
            val outcome =
                when {
                    !manual && subscription.tokenExpiresAtEpochMillis?.let { attemptedAt >= it } == true -> {
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
                    persistSuccess(group, attemptedAt, outcome)
                }

                is RefreshOutcome.Failure -> {
                    if (persistFailure(group, attemptedAt, outcome)) {
                        SubscriptionRefreshResult.Failed(outcome.failure, outcome.retry)
                    } else {
                        SubscriptionRefreshResult.Superseded
                    }
                }
            }
        }
    }

    private suspend fun persistSuccess(
        expected: ProxyGroup,
        attemptedAt: Long,
        outcome: RefreshOutcome.Updated,
    ): SubscriptionRefreshResult {
        val subscription = expected.subscription ?: return SubscriptionRefreshResult.Superseded
        val replacement = replacementGroup(expected, attemptedAt, outcome)
        val committed =
            awgProfileRepository.commitSubscriptionRefresh(expected, outcome.awgProfiles) { prepared ->
                replacement.copy(
                    subscription =
                        checkNotNull(replacement.subscription).copy(
                            migration =
                                receivedMigration(
                                    outcome.migration,
                                    outcome.payloadSha256,
                                    attemptedAt,
                                    replacement.members,
                                    prepared.map(SavedAwgProfile::toMigrationTarget),
                                    subscription.migration,
                                ),
                        ),
                )
            }
        return if (committed) {
            log.i { "refreshed subscription group ${expected.id}: ${replacement.members.size} profiles persisted" }
            SubscriptionRefreshResult.Updated(replacement.members.size)
        } else {
            SubscriptionRefreshResult.Superseded
        }
    }

    private fun replacementGroup(
        expected: ProxyGroup,
        attemptedAt: Long,
        outcome: RefreshOutcome.Updated,
    ): ProxyGroup {
        val subscription = checkNotNull(expected.subscription)
        val withMembers =
            if (outcome.clearRelayMembers) {
                expected.copy(members = emptyList(), cloudflareMemberIds = emptySet())
            } else {
                SubscriptionMemberPersistence.apply(
                    group = expected,
                    members = outcome.profiles,
                    failover = outcome.failover,
                    cloudflareMemberIds = outcome.cloudflareMemberIds,
                    isSelector = outcome.isSelector,
                )
            }
        // A server-given title replaces only the host fallback name, never a name the user chose.
        val serverTitle =
            outcome.profileTitle.takeIf { it.isNotEmpty() && expected.name == subscriptionHost(subscription.link) }
        return withMembers.copy(
            name = serverTitle ?: withMembers.name,
            packageRoutingRules = outcome.packageRoutingRules,
            subscription =
                subscription
                    .withUserinfoHeader(outcome.subscriptionUserinfo)
                    .copy(
                        autoUpdateDelay =
                            subscription.autoUpdateDelay.takeIf { it > 0L } ?: outcome.updateIntervalMinutes ?: 0L,
                        lastUpdated = attemptedAt,
                        lastRefreshAttemptAtEpochMillis = attemptedAt,
                        tokenExpiresAtEpochMillis = outcome.tokenExpiresAtEpochMillis,
                        lifecycleState = SubscriptionLifecycleState.ACTIVE,
                        lastRefreshFailure = null,
                        mirrors = outcome.subscriptionMirrors ?: subscription.mirrors,
                    ),
        )
    }

    private suspend fun persistFailure(
        expected: ProxyGroup,
        attemptedAt: Long,
        outcome: RefreshOutcome.Failure,
    ): Boolean {
        var installed = false
        repository.updateGroup(expected.id) { current ->
            if (current != expected) return@updateGroup current
            val subscription = current.subscription ?: return@updateGroup current
            installed = true
            current.copy(
                subscription =
                    subscription.copy(
                        lastRefreshAttemptAtEpochMillis = attemptedAt,
                        lifecycleState = outcome.lifecycleState ?: subscription.lifecycleState,
                        lastRefreshFailure =
                            subscription.lastRefreshFailure?.takeIf { it.isTerminal && !outcome.failure.isTerminal }
                                ?: outcome.failure,
                    ),
            )
        }
        if (installed) log.w { "subscription group ${expected.id} refresh failed: ${outcome.failure.name}" }
        return installed
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
                    val payload =
                        try {
                            response.body.readBoundedSubscriptionPayload()
                        } catch (_: SubscriptionPayloadTooLargeException) {
                            return@use payloadTooLargeFailure()
                        }
                    val body = payload.text
                    val singBoxResult = SingBoxSubscriptionParser.parse(body, group.id)
                    if (singBoxResult is SingBoxParseResult.Error && singBoxResult.recognizedBundle) {
                        return@use RefreshOutcome.Failure(SubscriptionRefreshFailure.PARSE_ERROR, null, false)
                    }
                    val singBox = singBoxResult as? SingBoxParseResult.Success
                    if (singBox?.migration != null) payload.requireMigrationUtf8()
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
                            profiles
                                .copy(
                                    awgProfiles = parsedWireGuardProfiles(singBox, wireGuard),
                                    subscriptionMirrors = singBox?.subscriptionMirrors,
                                    migration = singBox?.migration,
                                    payloadSha256 = payload.sha256,
                                ).withResponseHeaders(response)
                        }
                    } else {
                        RefreshOutcome.Failure(
                            failure = SubscriptionRefreshFailure.PARSE_ERROR,
                            lifecycleState = null,
                            retry = false,
                        )
                    }
                }
            } catch (_: IllegalArgumentException) {
                RefreshOutcome.Failure(
                    failure = SubscriptionRefreshFailure.PARSE_ERROR,
                    lifecycleState = null,
                    retry = false,
                )
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
        // A recognised bundle must not fall through to AWG-only success after
        // its relay topology or payload policy fails: that would erase the last working members.
        val rejectedTopology = singBox != null && selector is SelectorUrltestImportResult.Error
        val members =
            (selector as? SelectorUrltestImportResult.Success)
                ?.let { it.group?.members ?: it.profiles }
                .orEmpty()
                .filterNot { it is ProxyProfile.RawConfig }
        val hasRelayCandidates = singBox?.let { it.profiles.isNotEmpty() || it.skipped.isNotEmpty() } == true
        if (rejectedTopology || (hasRelayCandidates && members.isEmpty())) {
            return null
        }
        var parsed =
            if (selector is SelectorUrltestImportResult.Success && members.isNotEmpty()) {
                RefreshOutcome.Updated(
                    members,
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
        parsed = supportedProfileOutcome(parsed)
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

    private fun supportedProfileOutcome(parsed: RefreshOutcome.Updated?): RefreshOutcome.Updated? =
        parsed?.let { outcome ->
            outcome.profiles
                .filterNot { it is ProxyProfile.RawConfig }
                .takeIf { it.isNotEmpty() }
                ?.let { outcome.copy(profiles = it) }
        }

    private fun parsedWireGuardProfiles(
        singBox: SingBoxParseResult.Success?,
        wireGuardIni: com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionResult?,
    ): List<AwgSubscriptionProfile> =
        buildList {
            singBox?.amneziaWgProfiles?.forEach {
                add(it.toAwgSubscriptionProfile(previousTag = singBox.migration?.awgPreviousTags?.get(it.displayName)))
            }
            wireGuardIni?.amneziaWgProfiles?.forEach {
                add(it.toAwgSubscriptionProfile(memberId = "peer:${it.peerPublicKey}"))
            }
            wireGuardIni?.profiles?.forEach { add(it.toAwgSubscriptionProfile()) }
        }.also { it.requireValidSubscriptionProfiles() }

    private fun payloadTooLargeFailure(): RefreshOutcome.Failure =
        RefreshOutcome.Failure(
            failure = SubscriptionRefreshFailure.PAYLOAD_TOO_LARGE,
            lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
            retry = false,
        )

    private fun RefreshOutcome.Updated.withResponseHeaders(response: Response): RefreshOutcome.Updated =
        copy(
            subscriptionUserinfo = response.header(SubscriptionUserinfoHeader).orEmpty(),
            profileTitle = profileTitleOf(response.header(ProfileTitleHeader)),
            updateIntervalMinutes = updateIntervalMinutesOf(response.header(ProfileUpdateIntervalHeader)),
        )

    private fun SingBoxParseResult.Success?.orEmptyAwgCount(): Int = this?.amneziaWgProfiles?.size ?: 0

    private fun Int?.orZero(): Int = this ?: 0

    private sealed interface RefreshOutcome {
        data class Updated(
            val profiles: List<ProxyProfile>,
            val failover: SelectorFailover?,
            val awgProfiles: List<AwgSubscriptionProfile> = emptyList(),
            val subscriptionUserinfo: String = "",
            val profileTitle: String = "",
            val updateIntervalMinutes: Long? = null,
            val tokenExpiresAtEpochMillis: Long? = null,
            val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
            val clearRelayMembers: Boolean = false,
            val subscriptionMirrors: SubscriptionMirrorSet? = null,
            val cloudflareMemberIds: Set<String>? = null,
            val isSelector: Boolean = false,
            val migration: MigrationMetadata? = null,
            val payloadSha256: String = "",
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
        const val ProfileTitleHeader = "Profile-Title"
        const val ProfileUpdateIntervalHeader = "Profile-Update-Interval"
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

private const val MaxProfileTitleLength = 64
private const val MaxUpdateIntervalHours = 24L * 30L
private const val MinutesPerHour = 60L

/**
 * Decodes the `profile-title` header (plain text, or `base64:<utf-8 text>` as Hiddify and
 * the 3x-ui panels send it) into a bounded, control-free group name; `""` when unusable.
 */
internal fun profileTitleOf(header: String?): String {
    val raw = header?.trim().orEmpty()
    val text =
        if (raw.startsWith("base64:", ignoreCase = true)) {
            runCatching { String(Base64.getDecoder().decode(raw.substring("base64:".length).trim()), Charsets.UTF_8) }
                .getOrNull()
                .orEmpty()
        } else {
            raw
        }
    return text.filterNot { it.isISOControl() }.trim().take(MaxProfileTitleLength)
}

/** Converts the `profile-update-interval` header (whole hours) into minutes; `null` when absent or invalid. */
internal fun updateIntervalMinutesOf(header: String?): Long? =
    header
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it in 1L..MaxUpdateIntervalHours }
        ?.times(MinutesPerHour)
