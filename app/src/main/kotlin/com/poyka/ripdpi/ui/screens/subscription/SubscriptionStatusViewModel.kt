package com.poyka.ripdpi.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseMutationPreparationSource
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.SavedAwgProfile
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.proxyimport.subscriptionHost
import com.poyka.ripdpi.subscription.SubscriptionExpiryClock
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.subscription.SubscriptionRefreshCoordinator
import com.poyka.ripdpi.subscription.SubscriptionRefreshResult
import com.poyka.ripdpi.subscription.subscriptionDetailUiState
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import com.poyka.ripdpi.ui.screens.connection.awgProfileId
import com.poyka.ripdpi.ui.screens.connection.runningSelections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionStatusUiState(
    val summary: SubscriptionExpirySummaryUiState = SubscriptionExpirySummaryUiState(),
    val secretsRevealed: Boolean = false,
    val refreshingGroupId: String? = null,
    val refreshFailedGroupId: String? = null,
    /** The named cause of the failed manual refresh of [refreshFailedGroupId], when the payload was refused. */
    val refreshRejection: ImportRejection? = null,
    val deleteFailedGroupId: String? = null,
    val deletingGroupIds: ImmutableSet<String> = persistentSetOf(),
    /** Groups the running session carries traffic through, directly or by one of their AmneziaWG profiles. */
    val inUseGroupIds: ImmutableSet<String> = persistentSetOf(),
    /** Groups named after their subscription host or link, which the delete dialog does not repeat. */
    val addressNamedGroupIds: ImmutableSet<String> = persistentSetOf(),
)

@HiltViewModel
class SubscriptionStatusViewModel internal constructor(
    private val repository: ProxyGroupRepository,
    private val expiryClock: SubscriptionExpiryClock,
    private val profileMutations: PauseMutationPreparationSource,
    private val awgRepository: AwgProfileRepository,
    private val applied: AppliedRuntimeConfigurationSource,
    private val refreshGroup: suspend (String) -> SubscriptionRefreshResult,
) : ViewModel() {
    @Inject
    constructor(
        repository: ProxyGroupRepository,
        refreshCoordinator: SubscriptionRefreshCoordinator,
        expiryClock: SubscriptionExpiryClock,
        profileMutations: PauseMutationPreparationSource,
        awgRepository: AwgProfileRepository,
        applied: AppliedRuntimeConfigurationSource,
    ) : this(repository, expiryClock, profileMutations, awgRepository, applied, { groupId ->
        refreshCoordinator.refresh(groupId)
    })

    private val secretsRevealed = MutableStateFlow(false)
    private val refreshingGroupId = MutableStateFlow<String?>(null)
    private val refreshFailedGroupId = MutableStateFlow<String?>(null)
    private val deleteFailedGroupId = MutableStateFlow<String?>(null)
    private val deletingGroupIds = MutableStateFlow<Set<String>>(emptySet())

    // shortcut: the cause lives only in this screen's memory, persist it with the subscription if it must survive.
    private var refreshRejection: ImportRejection? = null

    val uiState: StateFlow<SubscriptionStatusUiState> =
        combine(
            repository.groups(),
            expiryClock.ticks(),
            secretsRevealed,
            refreshingGroupId,
            refreshFailedGroupId,
        ) { groups, nowMillis, revealed, refreshing, failedGroup ->
            val base = subscriptionExpiryUiState(groups, nowMillis)
            val byId = groups.associateBy { it.id }
            SubscriptionStatusUiState(
                summary =
                    base.copy(
                        items =
                            base.items
                                .map { item ->
                                    val subscription = byId[item.groupId]?.subscription
                                    if (subscription == null) {
                                        item
                                    } else {
                                        item.copy(
                                            details =
                                                subscriptionDetailUiState(
                                                    subscription,
                                                    revealSecrets = revealed,
                                                ),
                                        )
                                    }
                                }.toImmutableList(),
                    ),
                secretsRevealed = revealed,
                refreshingGroupId = refreshing,
                refreshFailedGroupId = failedGroup,
                refreshRejection = refreshRejection.takeIf { failedGroup != null },
                addressNamedGroupIds =
                    groups
                        .filter { group ->
                            group.subscription?.link?.let { group.name == it || group.name == subscriptionHost(it) } ==
                                true
                        }.map { it.id }
                        .toImmutableSet(),
            )
        }.combine(combine(deleteFailedGroupId, deletingGroupIds, ::Pair)) { state, (failed, deleting) ->
            state.copy(deleteFailedGroupId = failed, deletingGroupIds = deleting.toImmutableSet())
        }.combine(combine(applied.applications, awgRepository.observeProfiles(), ::inUseGroups)) { state, inUse ->
            state.copy(inUseGroupIds = inUse.toImmutableSet())
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SubscriptionStatusUiState(),
        )

    fun toggleSecrets() {
        secretsRevealed.value = !secretsRevealed.value
    }

    fun hideSecrets() {
        secretsRevealed.value = false
    }

    fun refresh(groupId: String) {
        if (!refreshingGroupId.compareAndSet(null, groupId)) return
        refreshFailedGroupId.value = null
        val job =
            viewModelScope.launch {
                try {
                    val result = refreshGroup(groupId)
                    if (result !is SubscriptionRefreshResult.Updated) {
                        refreshRejection = (result as? SubscriptionRefreshResult.Failed)?.rejection
                        refreshFailedGroupId.value = groupId
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    refreshRejection = null
                    refreshFailedGroupId.value = groupId
                }
            }
        job.invokeOnCompletion { refreshingGroupId.compareAndSet(groupId, null) }
    }

    /**
     * Deletes the group and the AmneziaWG profiles it imported. A repeat call while it runs, or a group the running
     * session uses, is ignored. The AmneziaWG profiles go first, so a failure leaves the group for a retry.
     */
    fun delete(groupId: String) {
        if (groupId in deletingGroupIds.getAndUpdate { it + groupId }) return
        deleteFailedGroupId.value = null
        val job =
            viewModelScope.launch {
                try {
                    if (groupId in inUseGroups(applied.applications.value, awgRepository.observeProfiles().first())) {
                        return@launch
                    }
                    awgRepository.deleteSubscriptionProfiles(groupId)
                    // Saved edit: the group is not in use, so the delete must not stop the runtime or cancel a pause.
                    repository.delete(profileMutations.captureMutation(ProfileMutationOrigin.SavedEdit), groupId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expectedFailure: Exception) {
                    Logger.w(tag = LogTags.UI) { "Subscription delete failed: ${expectedFailure::class.simpleName}" }
                    deleteFailedGroupId.value = groupId
                }
            }
        job.invokeOnCompletion { deletingGroupIds.update { it - groupId } }
    }

    override fun onCleared() {
        secretsRevealed.value = false
        super.onCleared()
    }
}

private fun inUseGroups(
    applications: Map<Mode, RuntimeConfigurationApplication>,
    awgProfiles: List<SavedAwgProfile>,
): Set<String> =
    applications.runningSelections().flatMapTo(mutableSetOf()) { selection ->
        val awgGroup = selection.awgProfileId()?.let { id -> awgProfiles.firstOrNull { it.id == id } }
        listOfNotNull(selection.selectorGroupId, awgGroup?.subscriptionGroupId)
    }
