package com.poyka.ripdpi.ui.screens.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.subscription.DeviceMigrationCoordinator
import com.poyka.ripdpi.subscription.MigrationCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class MigrationTargetUi(
    val tag: String,
    val awg: Boolean,
)

internal data class MigrationStatusUi(
    val deliveryId: String,
    val tested: Boolean,
    val targets: List<MigrationTargetUi>,
    val probeUrl: String,
    val busy: Boolean,
    val actionsEnabled: Boolean,
    val result: MigrationCheckResult?,
)

/** Only display-safe state leaves this model; receipt authority remains in sealed storage. */
@HiltViewModel
internal class MigrationStatusViewModel
    @Inject
    constructor(
        groups: ProxyGroupRepository,
        awg: AwgProfileRepository,
        private val coordinator: DeviceMigrationCoordinator,
    ) : ViewModel() {
        private val busyGroup = MutableStateFlow<String?>(null)
        private val results = MutableStateFlow<Map<String, MigrationCheckResult>>(emptyMap())
        private val receiptChannel = Channel<String>(Channel.BUFFERED)
        val receipts = receiptChannel.receiveAsFlow()

        val state =
            combine(groups.groups(), awg.observeProfiles(), busyGroup, results) { all, _, busy, outcomes ->
                buildMap {
                    for (group in all) {
                        val delivery = group.subscription?.migration ?: continue
                        // Export performs the same current-target check. No success survives an edited AWG row.
                        val tested = coordinator.hasCurrentTest(group.id)
                        put(
                            group.id,
                            MigrationStatusUi(
                                delivery.metadata.deliveryId,
                                tested,
                                delivery.targets.map { MigrationTargetUi(it.tag, it.amneziaWg) },
                                group.failover?.probeUrl.orEmpty(),
                                busy == group.id,
                                busy == null,
                                outcomes[group.id],
                            ),
                        )
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        fun check(
            groupId: String,
            tag: String,
            url: String,
        ) {
            if (!busyGroup.compareAndSet(null, groupId)) return
            results.value = results.value - groupId
            val job =
                viewModelScope.launch {
                    results.value = results.value + (groupId to coordinator.check(groupId, tag, url))
                }
            job.invokeOnCompletion { busyGroup.compareAndSet(groupId, null) }
        }

        fun export(groupId: String) {
            if (!busyGroup.compareAndSet(null, groupId)) return
            val job =
                viewModelScope.launch {
                    try {
                        val receipt = coordinator.receipt(groupId)
                        if (receipt == null) {
                            results.value = results.value + (groupId to MigrationCheckResult.Changed)
                        } else {
                            receiptChannel.send(receipt)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        results.value = results.value + (groupId to MigrationCheckResult.Failed)
                    }
                }
            job.invokeOnCompletion { busyGroup.compareAndSet(groupId, null) }
        }
    }
