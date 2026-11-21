package com.poyka.ripdpi.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.PauseMutationPreparationSource
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.subscription.ImportRejection
import com.poyka.ripdpi.subscription.SubscriptionExpiryClock
import com.poyka.ripdpi.subscription.SubscriptionExpirySummaryUiState
import com.poyka.ripdpi.subscription.SubscriptionRefreshCoordinator
import com.poyka.ripdpi.subscription.SubscriptionRefreshResult
import com.poyka.ripdpi.subscription.subscriptionDetailUiState
import com.poyka.ripdpi.subscription.subscriptionExpiryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
)

@HiltViewModel
class SubscriptionStatusViewModel internal constructor(
    private val repository: ProxyGroupRepository,
    private val expiryClock: SubscriptionExpiryClock,
    private val profileMutations: PauseMutationPreparationSource,
    private val refreshGroup: suspend (String) -> SubscriptionRefreshResult,
) : ViewModel() {
    @Inject
    constructor(
        repository: ProxyGroupRepository,
        refreshCoordinator: SubscriptionRefreshCoordinator,
        expiryClock: SubscriptionExpiryClock,
        profileMutations: PauseMutationPreparationSource,
    ) : this(repository, expiryClock, profileMutations, { groupId ->
        refreshCoordinator.refresh(groupId)
    })

    private val secretsRevealed = MutableStateFlow(false)
    private val refreshingGroupId = MutableStateFlow<String?>(null)
    private val refreshFailedGroupId = MutableStateFlow<String?>(null)
    private val deleteFailedGroupId = MutableStateFlow<String?>(null)

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
            )
        }.combine(deleteFailedGroupId) { state, deleteFailed -> state.copy(deleteFailedGroupId = deleteFailed) }
            .stateIn(
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

    fun delete(groupId: String) {
        deleteFailedGroupId.value = null
        viewModelScope.launch {
            try {
                repository.delete(profileMutations.captureMutation(ProfileMutationOrigin.ExplicitDeletion), groupId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                deleteFailedGroupId.value = groupId
            }
        }
    }

    override fun onCleared() {
        secretsRevealed.value = false
        super.onCleared()
    }
}
