package com.poyka.ripdpi.subscription

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
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
import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.MaxSubscriptionProfiles
import com.poyka.ripdpi.data.subscription.MigrationMetadata
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.data.subscription.SelectorUrltestGroupImport
import com.poyka.ripdpi.data.subscription.SelectorUrltestImportResult
import com.poyka.ripdpi.data.subscription.SingBoxBundleErrorCode
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.SubscriptionMirror
import com.poyka.ripdpi.data.subscription.SubscriptionMirrorSet
import com.poyka.ripdpi.data.subscription.SubscriptionPayload
import com.poyka.ripdpi.data.subscription.SubscriptionPayloadTooLargeException
import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import com.poyka.ripdpi.data.subscription.fitsSubscriptionLimits
import com.poyka.ripdpi.data.subscription.isValidForRefresh
import com.poyka.ripdpi.data.subscription.localSubscriptionPayload
import com.poyka.ripdpi.data.subscription.readBoundedSubscriptionPayload
import com.poyka.ripdpi.data.subscription.receivedMigration
import com.poyka.ripdpi.data.subscription.splitNativeProfiles
import com.poyka.ripdpi.data.subscription.toSelectorFailover
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
import java.util.UUID
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
    private val refreshNetworkHint: suspend (List<RipdpiFieldReport>) -> Unit,
) {
    @Inject
    internal constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionStatusNotifier,
        expiryClock: SubscriptionExpiryClock,
        networkContext: FieldReportNetworkContext,
        @ApplicationScope applicationScope: CoroutineScope,
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
    )

    @Suppress("LongParameterList")
    internal constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
        signalPublisher: SubscriptionSignalPublisher,
        httpClient: OkHttpClient,
        clockMillis: () -> Long,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit,
    ) : this(repository, awgProfileRepository, signalPublisher, httpClient, clockMillis, {})

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
                        SubscriptionRefreshResult.Failed(outcome.failure, outcome.retry, outcome.rejection)
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
        val body = payload.text
        val singBoxResult = SingBoxSubscriptionParser.parse(body, group.id)
        if (singBoxResult is SingBoxParseResult.Error && singBoxResult.recognizedBundle) {
            return rejected(
                ImportRejection
                    .SchemaVersion(singBoxResult.foundSchemaVersion)
                    .takeIf { singBoxResult.code == SingBoxBundleErrorCode.UNSUPPORTED_SCHEMA_VERSION },
            )
        }
        val singBox = singBoxResult as? SingBoxParseResult.Success
        if (singBox?.migration != null) payload.requireMigrationUtf8()
        if (singBox?.tokenExpiresAtEpochMillis?.let { clockMillis() >= it } == true) {
            return RefreshOutcome.Failure(
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
        require(wireGuard?.warnings.isNullOrEmpty())
        val wireGuardCount =
            singBox.orEmptyAwgCount() +
                wireGuard?.let { it.amneziaWgProfiles.size + it.profiles.size }.orZero()
        val profiles =
            when (val parsed = parseProfiles(body, group.id, wireGuardCount, singBox)) {
                is RefreshOutcome.Failure -> return parsed
                is RefreshOutcome.Updated -> parsed
            }
        // A refresh that would drop a saved server only because its new entry is refused keeps the saved group.
        val lost = profiles.skipped.firstOrNull { node -> group.members.any { it.displayName == node.label } }
        return if (lost != null) {
            rejected(ImportRejection.Entry(lost))
        } else if (!profiles.profiles.fitsSubscriptionLimits() || wireGuardCount > MaxSubscriptionProfiles) {
            payloadTooLargeFailure()
        } else {
            profiles.copy(
                awgProfiles = parsedWireGuardProfiles(singBox, wireGuard),
                subscriptionMirrors = singBox?.subscriptionMirrors,
                migration = singBox?.migration,
                payloadSha256 = payload.sha256,
            )
        }
    }

    /**
     * Installs a bundle the user holds locally (an opened file or pasted JSON). The group has no
     * link and auto-update off, so it is never fetched; a re-import with the same [name] updates
     * that group and its AmneziaWG rows in place. Nothing is saved when the bundle is rejected.
     */
    suspend fun importLocalBundle(
        name: String,
        text: String,
    ): SubscriptionRefreshResult {
        val groups = repository.list()
        val existing = groups.firstOrNull { it.isLocalBundle() && it.name == name }
        val draft =
            existing ?: ProxyGroup(
                id = UUID.randomUUID().toString(),
                name = name,
                type = ProxyGroupType.SUBSCRIPTION,
                order = groups.size,
                isSelector = false,
                subscription = Subscription(),
            )
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
        if (existing == null) repository.add(draft)
        val expected =
            repository.list().firstOrNull { it.id == draft.id } ?: return SubscriptionRefreshResult.Superseded
        val result = persistSuccess(expected, clockMillis(), outcome as RefreshOutcome.Updated)
        signalPublisher.publish(repository.list(), clockMillis())
        return result
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
    ): RefreshOutcome {
        val selector = SelectorUrltestGroupImport.import(body, groupId)
        val selected = selector as? SelectorUrltestImportResult.Success
        val (members, unusable) =
            splitNativeProfiles(
                selected?.let { it.group?.members ?: it.profiles }.orEmpty(),
                selected?.outboundTags.orEmpty(),
            )
        val skipped = selected?.skipped.orEmpty() + unusable
        // A recognised bundle must not fall through to AWG-only success after its relay topology or
        // payload policy fails, or after every relay entry is skipped: that would erase the last working members.
        val noUsableMember = members.isEmpty() && (singBox?.profiles?.isNotEmpty() == true || skipped.isNotEmpty())
        if (singBox != null && (selector is SelectorUrltestImportResult.Error || noUsableMember)) {
            val refused = (selector as? SelectorUrltestImportResult.Error)?.node ?: skipped.firstOrNull()
            return rejected(refused?.let(ImportRejection::Entry))
        }
        return when {
            selected != null && members.isNotEmpty() -> {
                RefreshOutcome.Updated(
                    members,
                    selected.failoverPolicy.toSelectorFailover(),
                    cloudflareMemberIds = selected.cloudflareMemberIds,
                    isSelector = selected.group != null,
                    // A skipped member cannot stay the initial selection or keep its health entry.
                    initialMemberId = selected.group?.initialMemberId?.takeIf { id -> members.any { it.id == id } },
                    memberHealth = selected.memberHealth.filterKeys { tag -> members.any { it.displayName == tag } },
                    fieldReport = selected.fieldReport,
                    tokenExpiresAtEpochMillis = singBox?.tokenExpiresAtEpochMillis,
                    packageRoutingRules = singBox?.packageRoutingRules.orEmpty(),
                    skipped = skipped,
                )
            }

            wireGuardCount == 0 -> {
                val simple = parseSimpleSubscription(body, groupId)
                when {
                    simple == null -> rejected(null)
                    simple.first.isEmpty() -> rejected(simple.second.firstOrNull()?.let(ImportRejection::Entry))
                    else -> RefreshOutcome.Updated(simple.first, null, skipped = simple.second)
                }
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

    private fun rejected(rejection: ImportRejection?): RefreshOutcome.Failure =
        RefreshOutcome.Failure(SubscriptionRefreshFailure.PARSE_ERROR, null, false, rejection)

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
            val tokenExpiresAtEpochMillis: Long? = null,
            val packageRoutingRules: List<PackageRoutingRule> = emptyList(),
            val clearRelayMembers: Boolean = false,
            val subscriptionMirrors: SubscriptionMirrorSet? = null,
            val cloudflareMemberIds: Set<String>? = null,
            val isSelector: Boolean = false,
            val initialMemberId: String? = null,
            val memberHealth: Map<String, EndpointHealth> = emptyMap(),
            val fieldReport: RipdpiFieldReport? = null,
            val migration: MigrationMetadata? = null,
            val payloadSha256: String = "",
            val skipped: List<SingBoxSkippedNode> = emptyList(),
        ) : RefreshOutcome

        data class Failure(
            val failure: SubscriptionRefreshFailure,
            val lifecycleState: SubscriptionLifecycleState?,
            val retry: Boolean,
            val rejection: ImportRejection? = null,
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

/** A locally imported bundle: no source link and never auto-updated. */
internal fun ProxyGroup.isLocalBundle(): Boolean =
    type == ProxyGroupType.SUBSCRIPTION && subscription?.let { it.link.isBlank() && !it.autoUpdate } == true

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
