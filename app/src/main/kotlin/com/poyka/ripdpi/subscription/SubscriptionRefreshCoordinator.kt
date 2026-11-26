package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
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
import com.poyka.ripdpi.data.subscription.DnsMorphFetcher
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.MaxSubscriptionProfiles
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.subscription.SubscriptionPayload
import com.poyka.ripdpi.data.subscription.SubscriptionPayloadTooLargeException
import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import com.poyka.ripdpi.data.subscription.deliver
import com.poyka.ripdpi.data.subscription.fitsSubscriptionLimits
import com.poyka.ripdpi.data.subscription.isValidForRefresh
import com.poyka.ripdpi.data.subscription.localSubscriptionPayload
import com.poyka.ripdpi.data.subscription.parseBundleDnsMorph
import com.poyka.ripdpi.data.subscription.readBoundedSubscriptionPayload
import com.poyka.ripdpi.data.subscription.receivedMigration
import com.poyka.ripdpi.data.subscription.splitNativeProfiles
import com.poyka.ripdpi.data.subscription.withUserinfoHeader
import com.poyka.ripdpi.proxyimport.subscriptionHost
import com.poyka.ripdpi.services.fieldreport.FieldReportNetworkContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        /** Entries left out because the native backend cannot run them. */
        val skipped: List<SingBoxSkippedNode> = emptyList(),
        /** The saved AmneziaWG profile this payload delivered without its private key, or `null`. */
        val awgKeyProfileId: String? = null,
    ) : SubscriptionRefreshResult

    data class Failed(
        val failure: SubscriptionRefreshFailure,
        val retryable: Boolean,
        /** The named cause of a rejected payload, when one is known. */
        val rejection: ImportRejection? = null,
        val httpStatus: Int? = null,
    ) : SubscriptionRefreshResult
}

class SubscriptionPreview internal constructor(
    val groupId: String,
    val link: String,
    val title: String,
    val serverCount: Int,
    internal val attemptedAt: Long,
    internal val outcome: RefreshOutcome.Updated,
)

sealed interface SubscriptionPreviewResult {
    data class Fetched(
        val preview: SubscriptionPreview,
    ) : SubscriptionPreviewResult

    data class Failed(
        val failure: SubscriptionRefreshFailure,
        val rejection: ImportRejection? = null,
        val httpStatus: Int? = null,
    ) : SubscriptionPreviewResult
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
    private val refreshNetworkHint: suspend (List<RipdpiFieldReport>) -> Unit,
    private val dnsMorphFetcher: DnsMorphFetcher,
) {
    @Inject
    internal constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionStatusNotifier,
        expiryClock: SubscriptionExpiryClock,
        networkContext: FieldReportNetworkContext,
        @ApplicationScope applicationScope: CoroutineScope,
        dnsMorphFetcher: DnsMorphFetcher,
    ) : this(
        repository = repository,
        awgProfileRepository = awgProfileRepository,
        signalPublisher = signalPublisher,
        httpClient = OkHttpClient(),
        clockMillis = expiryClock::nowMillis,
        // The fetch waits a random jitter plus its own timeout, so it runs beside the refresh, not inside it.
        refreshNetworkHint = { targets ->
            applicationScope.launch {
                try {
                    networkContext.refreshHint(targets)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    Logger.withTag("field-report").w { "network hint refresh failed: ${ignored.javaClass.simpleName}" }
                }
            }
        },
        dnsMorphFetcher = dnsMorphFetcher,
    )

    @Suppress("LongParameterList")
    internal constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionSignalPublisher,
        httpClient: OkHttpClient,
        clockMillis: () -> Long,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
        dnsMorphFetcher: DnsMorphFetcher = DnsMorphFetcher { throw IOException("DNS-Morph is not available") },
    ) : this(repository, awgProfileRepository, signalPublisher, httpClient, clockMillis, {}, dnsMorphFetcher)

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
        val refreshed = repository.list()
        // The field-report network hint is fetched with the scheduled bundle refresh; it never fails the refresh.
        try {
            refreshNetworkHint(refreshed.mapNotNull { it.fieldReport })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            log.w { "network hint refresh failed: ${ignored.javaClass.simpleName}" }
        }
        signalPublisher.publish(refreshed, clockMillis())
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

    suspend fun preview(
        groupId: String,
        link: String,
    ): SubscriptionPreviewResult {
        val subscription = Subscription(link = link, kind = SubscriptionKind.LONG_LIVED, autoUpdate = true)
        val group =
            ProxyGroup(
                id = groupId,
                name = subscriptionHost(link),
                type = ProxyGroupType.SUBSCRIPTION,
                order = 0,
                isSelector = false,
                subscription = subscription,
            )
        val attemptedAt = clockMillis()
        return when (val outcome = fetchMirrors(group, subscription)) {
            is RefreshOutcome.Updated -> {
                SubscriptionPreviewResult.Fetched(
                    SubscriptionPreview(
                        groupId = groupId,
                        link = link,
                        title = outcome.profileTitle,
                        serverCount = outcome.profiles.size + outcome.awgProfiles.size,
                        attemptedAt = attemptedAt,
                        outcome = outcome,
                    ),
                )
            }

            is RefreshOutcome.Failure -> {
                SubscriptionPreviewResult.Failed(outcome.failure, outcome.rejection, outcome.httpStatus)
            }
        }
    }

    suspend fun install(preview: SubscriptionPreview): SubscriptionRefreshResult {
        val group =
            repository.list().firstOrNull { it.id == preview.groupId && it.subscription?.link == preview.link }
                ?: return SubscriptionRefreshResult.Superseded
        val result = persistSuccess(group, preview.attemptedAt, preview.outcome)
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
        } else if (subscription.link.isBlank()) {
            // A local import or a redacted backup has no source; recording UNREACHABLE would be false.
            SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.UNREACHABLE, retryable = false)
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
                        SubscriptionRefreshResult.Failed(
                            outcome.failure,
                            outcome.retry,
                            outcome.rejection,
                            outcome.httpStatus,
                        )
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
        var awgKeyProfileId: String? = null
        val committed =
            awgProfileRepository.commitSubscriptionRefresh(expected, outcome.awgProfiles) { prepared ->
                awgKeyProfileId = prepared.firstOrNull { it.request.privateKey.isBlank() }?.id
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
            SubscriptionRefreshResult.Updated(replacement.members.size, outcome.skipped, awgKeyProfileId)
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
                expected.copy(
                    members = emptyList(),
                    cloudflareMemberIds = emptySet(),
                    initialMemberId = null,
                    memberHealth = emptyMap(),
                    fieldReport = null,
                )
            } else {
                SubscriptionMemberPersistence.apply(
                    group = expected,
                    members = outcome.profiles,
                    failover = outcome.failover,
                    cloudflareMemberIds = outcome.cloudflareMemberIds,
                    isSelector = outcome.isSelector,
                    initialMemberId = outcome.initialMemberId,
                    memberHealth = outcome.memberHealth,
                    fieldReport = outcome.fieldReport,
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
                        lastUpdated = attemptedAt,
                        lastRefreshAttemptAtEpochMillis = attemptedAt,
                        tokenExpiresAtEpochMillis = outcome.tokenExpiresAtEpochMillis,
                        lifecycleState = SubscriptionLifecycleState.ACTIVE,
                        lastRefreshFailure = null,
                        mirrors = outcome.subscriptionMirrors ?: subscription.mirrors,
                        localBundleTags = outcome.localBundleTags ?: subscription.localBundleTags,
                        dnsMorph = outcome.dnsMorph,
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
                    classifyHttpFailure(response.code, group)?.let { return@use it.copy(httpStatus = response.code) }
                    val payload =
                        try {
                            response.body.readBoundedSubscriptionPayload()
                        } catch (_: SubscriptionPayloadTooLargeException) {
                            return@use payloadTooLargeFailure()
                        }
                    when (val outcome = parsePayload(group, payload)) {
                        is RefreshOutcome.Updated -> outcome.withResponseHeaders(response)
                        is RefreshOutcome.Failure -> outcome
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

    /** Shared by the HTTPS fetch and local imports so both persist the same parse result. */
    private suspend fun parsePayload(
        group: ProxyGroup,
        payload: SubscriptionPayload,
    ): RefreshOutcome {
        val singBoxResult = SingBoxSubscriptionParser.parse(payload.text, group.id)
        val singBox = singBoxResult as? SingBoxParseResult.Success
        if (singBox?.migration != null) payload.requireMigrationUtf8()
        val expired = singBox?.tokenExpiresAtEpochMillis?.let { clockMillis() >= it } == true
        return bundleRejection(singBoxResult, expired) ?: parseAcceptedPayload(group, payload, singBox)
    }

    private suspend fun parseAcceptedPayload(
        group: ProxyGroup,
        payload: SubscriptionPayload,
        singBox: SingBoxParseResult.Success?,
    ): RefreshOutcome {
        val body = payload.text
        val wireGuard =
            if (WireGuardIniSubscriptionParser.looksLikeWireGuardIni(body)) {
                WireGuardIniSubscriptionParser.parse(body, group.id)
            } else {
                null
            }
        require(wireGuard?.warnings.isNullOrEmpty())
        val wireGuardCount =
            singBox.orEmptyAwgCount() +
                wireGuard?.let { it.amneziaWgProfiles.size + it.profiles.size }.orZero()
        val profiles =
            when (val parsed = parseProfiles(body, group.id, wireGuardCount, singBox)) {
                is RefreshOutcome.Failure -> return parsed
                is RefreshOutcome.Updated -> parsed
            }
        // A payload with refused entries never shrinks the saved group: a server that is missing from the
        // usable set may be one of the refused entries, so the saved group is kept and the entry is named.
        val kept = profiles.profiles.mapTo(mutableSetOf()) { it.displayName }
        val dropsSaved = profiles.skipped.isNotEmpty() && group.members.any { it.displayName !in kept }
        return if (dropsSaved) {
            val refused =
                profiles.skipped.firstOrNull { node -> group.members.any { it.displayName == node.label } }
                    ?: profiles.skipped.first()
            rejected(ImportRejection.Entry(refused))
        } else if (!profiles.profiles.fitsSubscriptionLimits() || wireGuardCount > MaxSubscriptionProfiles) {
            payloadTooLargeFailure()
        } else {
            profiles.copy(
                awgProfiles = parsedWireGuardProfiles(singBox, wireGuard),
                subscriptionMirrors = singBox?.subscriptionMirrors,
                dnsMorph = parseBundleDnsMorph(body),
                migration = singBox?.migration,
                payloadSha256 = payload.sha256,
            )
        }
    }

    /**
     * Installs a bundle the user holds locally (an opened file or pasted JSON). The group has no
     * link and auto-update off, so it is never fetched. A re-import of the same bundle updates that
     * group and its AmneziaWG rows in place, whatever the file is called (see [localBundleTarget]).
     * Nothing is saved when the bundle is rejected.
     */
    suspend fun importLocalBundle(
        name: String,
        text: String,
    ): SubscriptionRefreshResult {
        val groups = repository.list()
        val tags = withContext(Dispatchers.Default) { localBundleTags(text) }
        val draft = groups.localBundleTarget(name, tags)
        val outcome =
            try {
                withContext(Dispatchers.Default) { parsePayload(draft, localSubscriptionPayload(text)) }
            } catch (_: SubscriptionPayloadTooLargeException) {
                payloadTooLargeFailure()
            } catch (_: IllegalArgumentException) {
                RefreshOutcome.Failure(SubscriptionRefreshFailure.PARSE_ERROR, null, false)
            }
        if (outcome is RefreshOutcome.Failure) {
            return SubscriptionRefreshResult.Failed(outcome.failure, false, outcome.rejection)
        }
        if (groups.none { it.id == draft.id }) repository.add(draft)
        val updated = (outcome as RefreshOutcome.Updated).copy(localBundleTags = tags)
        return repository.list().firstOrNull { it.id == draft.id }?.let { expected ->
            persistSuccess(expected, clockMillis(), updated).also {
                signalPublisher.publish(repository.list(), clockMillis())
            }
        } ?: SubscriptionRefreshResult.Superseded
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
        // The bundle-declared DNS-Morph channel runs only after every HTTPS endpoint failed.
        return subscription.dnsMorph?.let { bridge ->
            dnsMorphFetcher.deliver(bridge) { parsePayload(group, it) as? RefreshOutcome.Updated }
        } ?: (last as RefreshOutcome.Failure).copy(retry = retry)
    }

    private suspend fun parseProfiles(
        body: String,
        groupId: String,
        wireGuardCount: Int,
        singBox: SingBoxParseResult.Success?,
    ): RefreshOutcome {
        val selector = SelectorUrltestGroupImport.import(body, groupId)
        val selected = selector as? SelectorUrltestImportResult.Success
        val (members, unusable) =
            splitNativeProfiles(
                selected?.let { it.group?.members ?: it.profiles }.orEmpty(),
                selected?.outboundTags.orEmpty(),
            )
        val skipped = selected?.skipped.orEmpty() + unusable
        return bundleRefusal(selector, members, unusable, skipped, singBox) ?: when {
            selected != null && members.isNotEmpty() -> {
                selectorOutcome(selected, members, skipped, singBox)
            }

            wireGuardCount == 0 -> {
                simpleSubscriptionOutcome(body, groupId)
            }

            else -> {
                RefreshOutcome.Updated(
                    emptyList(),
                    null,
                    tokenExpiresAtEpochMillis = singBox?.tokenExpiresAtEpochMillis,
                    packageRoutingRules = singBox?.packageRoutingRules.orEmpty(),
                    clearRelayMembers = true,
                )
            }
        }
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

    private fun RefreshOutcome.Updated.withResponseHeaders(response: Response): RefreshOutcome.Updated =
        copy(
            subscriptionUserinfo = response.header(SubscriptionUserinfoHeader).orEmpty(),
            profileTitle = profileTitleOf(response.header(ProfileTitleHeader)),
        )

    private fun SingBoxParseResult.Success?.orEmptyAwgCount(): Int = this?.amneziaWgProfiles?.size ?: 0

    private fun Int?.orZero(): Int = this ?: 0

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
    // Bidi and other format characters could make a server title spoof another group's name.
    val clean = text.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
    val bounded = clean.take(MaxProfileTitleLength)
    return if (bounded.lastOrNull()?.isHighSurrogate() == true) bounded.dropLast(1) else bounded
}
