package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.PauseMutationPreparationSource
import com.poyka.ripdpi.data.ProfileMutationOrigin
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
import com.poyka.ripdpi.data.awg.saveMigrationTargets
import com.poyka.ripdpi.data.awg.toAwgSubscriptionProfile
import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.subscription.AmneziaWgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.BootstrapConsumeResult
import com.poyka.ripdpi.data.subscription.BootstrapConsumer
import com.poyka.ripdpi.data.subscription.EndpointHealth
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.data.subscription.RipdpiFieldReport
import com.poyka.ripdpi.data.subscription.SingBoxSkippedNode
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
import com.poyka.ripdpi.proxyimport.subscriptionHost
import com.poyka.ripdpi.subscription.SubscriptionAutoUpdateScheduler
import com.poyka.ripdpi.subscription.SubscriptionPreview
import com.poyka.ripdpi.subscription.SubscriptionPreviewResult
import com.poyka.ripdpi.subscription.SubscriptionRefreshCoordinator
import com.poyka.ripdpi.subscription.SubscriptionRefreshResult
import com.poyka.ripdpi.subscription.isLocalBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** UI state for the subscription-add import-confirmation screen. */
data class SubscriptionImportConfirmUiState(
    val url: String = "",
    val name: String = "",
    val bootstrap: Boolean = false,
    val importing: Boolean = false,
    val importFailed: Boolean = false,
    /** The named cause of [importFailed], when the payload itself was refused. */
    val rejection: ImportRejection? = null,
    val refreshFailure: SubscriptionRefreshFailure? = null,
    val httpStatus: Int? = null,
    val fetchedTitle: String = "",
    val serverCount: Int? = null,
    val checked: Boolean = false,
    /** Set once the import is saved with entries left out; the screen stays to show them. */
    val skipped: ImmutableList<SingBoxSkippedNode> = persistentListOf(),
    /** The imported AmneziaWG profile that still needs its private key, or empty; opened when the screen closes. */
    val awgKeyProfileId: String = "",
    /** A RIPDPI bundle the user holds locally; it has no [url]. */
    val localBundle: Boolean = false,
) {
    val needsCheck: Boolean
        get() = !bootstrap && !localBundle && url.isNotBlank() && !checked
}

/**
 * Backing [ViewModel] for the subscription-add import-confirmation destination.
 *
 * This is the import-confirmation surface, not a full subscription editor: it shows the
 * pre-filled URL + name (and a bootstrap badge when the deep link's URL path was
 * `/bootstrap/...`) and an "Add" action that persists a [ProxyGroupType.SUBSCRIPTION]
 * group via [ProxyGroupRepository]. The user confirms here before any network request is
 * made. The handler activity navigates here after parsing a
 * `singbox://import-remote-profile?url=…` deep link.
 */
@HiltViewModel
class SubscriptionImportConfirmViewModel
    private constructor(
        private val repository: ProxyGroupRepository,
        private val bootstrapConsumer: BootstrapConsumer,
        private val groupIdFactory: () -> String,
        private val saveAwgProfiles: suspend (
            List<AmneziaWgSubscriptionProfile>,
            Map<String, String>,
        ) -> List<com.poyka.ripdpi.data.subscription.MigrationTarget>,
        private val refreshSubscription: suspend (String) -> SubscriptionRefreshResult,
        private val scheduleAutoUpdate: (List<ProxyGroup>) -> Unit,
        private val discardGroup: suspend (String) -> Unit,
        private val importLocalBundle: suspend (String, String) -> SubscriptionRefreshResult,
        private val awgKeyMissing: suspend (String) -> Boolean,
        private val previewSubscription: suspend (String, String) -> SubscriptionPreviewResult,
        private val installPreview: suspend (SubscriptionPreview) -> SubscriptionRefreshResult,
        @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit,
    ) : ViewModel() {
        @Inject
        constructor(
            repository: ProxyGroupRepository,
            awgProfileRepository: AwgProfileRepository,
            refreshCoordinator: SubscriptionRefreshCoordinator,
            autoUpdateScheduler: SubscriptionAutoUpdateScheduler,
            profileMutations: PauseMutationPreparationSource,
        ) : this(
            repository = repository,
            bootstrapConsumer = BootstrapConsumer(),
            groupIdFactory = { UUID.randomUUID().toString() },
            saveAwgProfiles = { profiles, previousTags ->
                awgProfileRepository.saveMigrationTargets(
                    profiles.map {
                        it.toAwgSubscriptionProfile(previousTag = previousTags[it.displayName])
                    },
                )
            },
            refreshSubscription = refreshCoordinator::refresh,
            scheduleAutoUpdate = autoUpdateScheduler::schedule,
            discardGroup = { groupId ->
                repository.delete(profileMutations.captureMutation(ProfileMutationOrigin.Compensation), groupId)
            },
            importLocalBundle = refreshCoordinator::importLocalBundle,
            awgKeyMissing = { profileId ->
                awgProfileRepository
                    .load(profileId)
                    ?.request
                    ?.privateKey
                    ?.isBlank() == true
            },
            previewSubscription = refreshCoordinator::preview,
            installPreview = refreshCoordinator::install,
            constructorMarker = Unit,
        )

        internal constructor(
            repository: ProxyGroupRepository,
            bootstrapConsumer: BootstrapConsumer = BootstrapConsumer(),
            groupIdFactory: () -> String = { UUID.randomUUID().toString() },
            saveAwgProfiles: suspend (
                List<AmneziaWgSubscriptionProfile>,
                Map<String, String>,
            ) -> List<com.poyka.ripdpi.data.subscription.MigrationTarget> = { _, _ -> emptyList() },
            refreshSubscription: suspend (
                String,
            ) -> SubscriptionRefreshResult = { error("No test refresh") },
            scheduleAutoUpdate: (List<ProxyGroup>) -> Unit = {},
            discardGroup: suspend (String) -> Unit = {},
            importLocalBundle: suspend (
                String,
                String,
            ) -> SubscriptionRefreshResult = { _, _ -> error("No test import") },
            awgKeyMissing: suspend (String) -> Boolean = { false },
            previewSubscription: suspend (
                String,
                String,
            ) -> SubscriptionPreviewResult = { _, _ -> error("No test preview") },
            installPreview: suspend (SubscriptionPreview) -> SubscriptionRefreshResult = { error("No test install") },
        ) : this(
            repository,
            bootstrapConsumer,
            groupIdFactory,
            saveAwgProfiles,
            refreshSubscription,
            scheduleAutoUpdate,
            discardGroup,
            importLocalBundle,
            awgKeyMissing,
            previewSubscription,
            installPreview,
            Unit,
        )

        private val _uiState = MutableStateFlow(SubscriptionImportConfirmUiState())
        val uiState: StateFlow<SubscriptionImportConfirmUiState> = _uiState.asStateFlow()
        private val importedEventChannel = Channel<String>(capacity = Channel.BUFFERED)

        /** Emits once the import is saved: the AmneziaWG profile id that still needs its private key, or empty. */
        val importedEvents: Flow<String> = importedEventChannel.receiveAsFlow()
        private var completed = false
        private var claimedImportToken: String? = null

        // Kept out of the UI state: the bundle carries credentials.
        private var localBundleText: String? = null

        private var preview: SubscriptionPreview? = null

        // A consumed one-time payload that is not saved yet: a retry saves it without spending the token again.
        private var unsavedBootstrap: UnsavedBootstrap? = null

        internal fun claimRequest(
            importToken: String,
            pendingImports: PendingProxyImportStore = PendingProxyImportStore.process,
        ): Boolean {
            val alreadyClaimed =
                claimedImportToken == importToken && (_uiState.value.url.isNotBlank() || localBundleText != null)
            val request = if (alreadyClaimed) null else pendingImports.claimSubscription(importToken)
            return when {
                alreadyClaimed -> {
                    true
                }

                request == null -> {
                    false
                }

                else -> {
                    claimedImportToken = importToken
                    // The card shows the same name the local import saves.
                    val name =
                        if (request.bundle !=
                            null
                        ) {
                            request.name.ifBlank { LocalBundleDefaultName }
                        } else {
                            request.name
                        }
                    setRequest(request.url, name, request.bootstrap)
                    localBundleText = request.bundle
                    _uiState.update { it.copy(localBundle = request.bundle != null) }
                    true
                }
            }
        }

        /** Seeds the screen with the deep link's pre-filled fields. */
        fun setRequest(
            url: String,
            name: String,
            bootstrap: Boolean,
        ) {
            completed = false
            localBundleText = null
            preview = null
            _uiState.update {
                SubscriptionImportConfirmUiState(url = url, name = name, bootstrap = bootstrap)
            }
        }

        fun check() {
            val state = _uiState.value
            if (!state.needsCheck || state.importing || completed) return
            _uiState.update { it.clearedFailure().copy(importing = true) }
            viewModelScope.launch {
                try {
                    val groupId =
                        repository.list().firstOrNull { it.subscription?.link == state.url }?.id ?: groupIdFactory()
                    val result = previewSubscription(groupId, state.url)
                    if (_uiState.value.url != state.url) return@launch
                    when (result) {
                        is SubscriptionPreviewResult.Fetched -> {
                            preview = result.preview
                            _uiState.update {
                                it.copy(fetchedTitle = result.preview.title, serverCount = result.preview.serverCount)
                            }
                        }

                        is SubscriptionPreviewResult.Failed -> {
                            _uiState.update {
                                it.copy(
                                    importFailed = true,
                                    rejection = result.rejection,
                                    refreshFailure = result.failure,
                                    httpStatus = result.httpStatus,
                                )
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _uiState.update { it.copy(importFailed = true) }
                } finally {
                    _uiState.update { if (it.url == state.url) it.copy(importing = false, checked = true) else it }
                }
            }
        }

        /**
         * Persists the pending subscription into a new [ProxyGroupType.SUBSCRIPTION]
         * group. Ordinary links are refreshed before success is emitted. Bootstrap
         * links are consumed once, so their payload and consumed timestamp reach storage together.
         *
         * The persisted [Subscription.kind] reflects the bootstrap flag: a bootstrap
         * import is stored as [SubscriptionKind.BOOTSTRAP] so the auto-update worker
         * skips it and the UI can mark it as a one-time token; everything else is a
         * refetchable [SubscriptionKind.LONG_LIVED] subscription.
         */
        fun confirm() {
            val state = _uiState.value
            val bundle = localBundleText
            if (state.url.isBlank() && bundle == null) return
            if (state.importing || completed) return
            _uiState.update { it.clearedFailure().copy(importing = true) }
            viewModelScope.launch {
                try {
                    if (bundle != null) {
                        importBundle(state.name, bundle)
                        return@launch
                    }
                    val groups = repository.list()
                    val existing = groups.firstOrNull { group -> group.subscription?.link == state.url }
                    val groupId = preview?.takeIf { it.link == state.url }?.groupId ?: existing?.id ?: groupIdFactory()
                    val groupName =
                        state.name.takeIf { it.isNotBlank() } ?: subscriptionHost(state.url).ifEmpty { state.url }
                    val order = existing?.order ?: groups.size
                    if (state.bootstrap && existing?.subscription?.consumedAt == null) {
                        consumeBootstrap(state, existing, groupId, groupName, order)
                    } else {
                        val subscription =
                            existing?.subscription ?: Subscription(
                                link = state.url,
                                kind = SubscriptionKind.LONG_LIVED,
                                autoUpdate = true,
                            )
                        repository.add(
                            subscriptionGroup(
                                existing = existing,
                                groupId = groupId,
                                groupName = groupName,
                                order = order,
                                subscription = subscription,
                            ),
                        )
                        if (subscription.kind == SubscriptionKind.BOOTSTRAP && subscription.consumedAt != null) {
                            if (subscription.lastRefreshFailure == SubscriptionRefreshFailure.PARSE_ERROR) {
                                _uiState.update { it.copy(importFailed = true) }
                            } else {
                                completeImport()
                            }
                        } else {
                            refreshAndCompleteImport(groupId, isNewGroup = existing == null)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _uiState.update { it.copy(importFailed = true) }
                } finally {
                    _uiState.update { it.copy(importing = false) }
                }
            }
        }

        /** A rejected or failed local import leaves no new group behind (same cleanup as the URL path). */
        private suspend fun importBundle(
            name: String,
            bundle: String,
        ) {
            val before = repository.list().mapTo(mutableSetOf()) { it.id }
            val result =
                try {
                    importLocalBundle(name, bundle)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            if (result is SubscriptionRefreshResult.Updated) {
                localBundleText = null
                completeImport(result.skipped, result.awgKeyProfileId)
                return
            }
            // The import may have matched a group under another name, so only a group it added is new.
            repository.list().filter { it.isLocalBundle() && it.id !in before }.forEach { discardGroup(it.id) }
            _uiState.update {
                it.copy(importFailed = true, rejection = (result as? SubscriptionRefreshResult.Failed)?.rejection)
            }
        }

        override fun onCleared() {
            localBundleText = null
            super.onCleared()
        }

        private suspend fun refreshAndCompleteImport(
            groupId: String,
            isNewGroup: Boolean,
        ) {
            val checked = preview?.takeIf { it.groupId == groupId && it.link == _uiState.value.url }
            val result = if (checked != null) installPreview(checked) else refreshSubscription(groupId)
            // A first fetch that is not a subscription at all (a web page, say) leaves nothing
            // worth retrying: drop the new group so auto-update never polls that URL again.
            if (isNewGroup && result is SubscriptionRefreshResult.Failed && result.failure in NotASubscription) {
                discardGroup(groupId)
            }
            // Schedule from the saved result so terminal failures and opt-outs stay excluded.
            scheduleAutoUpdate(repository.list())
            when (result) {
                is SubscriptionRefreshResult.Updated -> {
                    completeImport(result.skipped, result.awgKeyProfileId)
                }

                is SubscriptionRefreshResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            importFailed = true,
                            rejection = result.rejection,
                            refreshFailure = result.failure,
                            httpStatus = result.httpStatus,
                        )
                    }
                }

                SubscriptionRefreshResult.Superseded -> {
                    _uiState.update { it.copy(importFailed = true) }
                }
            }
        }

        private suspend fun consumeBootstrap(
            state: SubscriptionImportConfirmUiState,
            existing: ProxyGroup?,
            groupId: String,
            groupName: String,
            order: Int,
        ) {
            // The server spends the token once it answers, so finish the save even if the screen closes.
            // `saved` is null on failure, else the AmneziaWG profile id that still needs its key (or empty).
            val saved: String? =
                withContext(NonCancellable) {
                    val unsaved = unsavedBootstrap?.takeIf { it.url == state.url }
                    val targetGroupId = unsaved?.groupId ?: groupId
                    when (val result = unsaved?.result ?: bootstrapConsumer.consume(state.url, targetGroupId)) {
                        is BootstrapConsumeResult.Consumed -> {
                            unsavedBootstrap = UnsavedBootstrap(state.url, targetGroupId, result)
                            val awgTargets =
                                saveAwgProfiles(result.amneziaWgProfiles, result.migration?.awgPreviousTags.orEmpty())
                            repository.add(
                                subscriptionGroup(
                                    existing = existing,
                                    groupId = targetGroupId,
                                    groupName = groupName,
                                    order = order,
                                    subscription =
                                        Subscription(
                                            link = state.url,
                                            kind = SubscriptionKind.BOOTSTRAP,
                                            consumedAt = result.consumedAtMillis,
                                            mirrors = result.subscriptionMirrors,
                                            migration =
                                                com.poyka.ripdpi.data.subscription.receivedMigration(
                                                    result.migration,
                                                    result.payloadSha256,
                                                    result.consumedAtMillis,
                                                    result.profiles,
                                                    awgTargets,
                                                ),
                                        ),
                                    members = result.profiles,
                                    packageRoutingRules = result.packageRoutingRules,
                                    cloudflareMemberIds = result.cloudflareMemberIds,
                                    isSelector = result.isSelector,
                                    initialMemberId = result.initialMemberId,
                                    memberHealth = result.memberHealth,
                                    fieldReport = result.fieldReport,
                                    failover = result.failover,
                                ),
                            )
                            unsavedBootstrap = null
                            awgTargets.firstOrNull { awgKeyMissing(it.profileId) }?.profileId.orEmpty()
                        }

                        is BootstrapConsumeResult.ParseError -> {
                            result.consumedAtMillis?.let { consumedAt ->
                                saveRejectedBootstrap(state, existing, targetGroupId, groupName, order, consumedAt)
                            }
                            _uiState.update {
                                it.copy(importFailed = true, rejection = result.node?.let(ImportRejection::Entry))
                            }
                            null
                        }

                        is BootstrapConsumeResult.AlreadyConsumed -> {
                            _uiState.update {
                                it.copy(importFailed = true, refreshFailure = SubscriptionRefreshFailure.INVALIDATED)
                            }
                            null
                        }

                        is BootstrapConsumeResult.NetworkError -> {
                            _uiState.update {
                                it.copy(
                                    importFailed = true,
                                    refreshFailure = SubscriptionRefreshFailure.NETWORK_ERROR,
                                    httpStatus = result.httpStatus,
                                )
                            }
                            null
                        }
                    }
                }
            if (saved != null) completeImport(awgKeyProfileId = saved)
        }

        private suspend fun saveRejectedBootstrap(
            state: SubscriptionImportConfirmUiState,
            existing: ProxyGroup?,
            groupId: String,
            groupName: String,
            order: Int,
            consumedAt: Long,
        ) {
            val receipt: (Subscription) -> Subscription = { subscription ->
                subscription.copy(
                    kind = SubscriptionKind.BOOTSTRAP,
                    consumedAt = consumedAt,
                    lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
                    lastRefreshFailure = SubscriptionRefreshFailure.PARSE_ERROR,
                )
            }
            if (existing == null) {
                repository.add(
                    subscriptionGroup(null, groupId, groupName, order, receipt(Subscription(link = state.url))),
                )
            } else {
                repository.updateGroup(groupId) { current ->
                    current.subscription
                        ?.takeIf {
                            it.link == state.url && it == existing.subscription &&
                                current.ownershipGeneration == existing.ownershipGeneration
                        }?.let {
                            current.copy(subscription = receipt(it))
                        } ?: current
                }
            }
        }

        private class UnsavedBootstrap(
            val url: String,
            val groupId: String,
            val result: BootstrapConsumeResult.Consumed,
        )

        private fun subscriptionGroup(
            existing: ProxyGroup?,
            groupId: String,
            groupName: String,
            order: Int,
            subscription: Subscription,
            members: List<ProxyProfile> = existing?.members.orEmpty(),
            packageRoutingRules: List<PackageRoutingRule> = existing?.packageRoutingRules.orEmpty(),
            cloudflareMemberIds: Set<String> = existing?.cloudflareMemberIds.orEmpty(),
            isSelector: Boolean = existing?.isSelector ?: false,
            failover: SelectorFailover? = existing?.failover,
            initialMemberId: String? = existing?.initialMemberId,
            memberHealth: Map<String, EndpointHealth> = existing?.memberHealth.orEmpty(),
            fieldReport: RipdpiFieldReport? = existing?.fieldReport,
        ): ProxyGroup =
            ProxyGroup(
                id = groupId,
                name = groupName,
                type = ProxyGroupType.SUBSCRIPTION,
                order = order,
                isSelector = isSelector,
                subscription = subscription,
                members = members,
                failover = failover,
                initialMemberId = initialMemberId,
                memberHealth = memberHealth,
                fieldReport = fieldReport,
                cloudflareMemberIds = cloudflareMemberIds,
                packageRoutingRules = packageRoutingRules,
            )

        private suspend fun completeImport(
            skipped: List<SingBoxSkippedNode> = emptyList(),
            awgKeyProfileId: String? = null,
        ) {
            completed = true
            val keyProfileId = awgKeyProfileId.orEmpty()
            if (skipped.isEmpty()) {
                importedEventChannel.send(keyProfileId)
            } else {
                _uiState.update { it.copy(skipped = skipped.toImmutableList(), awgKeyProfileId = keyProfileId) }
            }
        }
    }

private fun SubscriptionImportConfirmUiState.clearedFailure() =
    copy(importFailed = false, rejection = null, refreshFailure = null, httpStatus = null)

private val NotASubscription = setOf(SubscriptionRefreshFailure.PARSE_ERROR)

/** Group name for a local bundle without a name; re-importing it updates the same group. */
internal const val LocalBundleDefaultName = "RIPDPI"
