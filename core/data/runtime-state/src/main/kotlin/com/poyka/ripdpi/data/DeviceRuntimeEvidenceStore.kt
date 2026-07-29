package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DeviceRuntimeLifecyclePhase {
    Created,
    StartCommand,
    Destroyed,
}

enum class DeviceRuntimeForegroundCallKind {
    Initial,
    Refresh,
}

enum class DeviceRuntimeForegroundOutcome {
    Returned,
    StartNotAllowed,
    SecurityRejected,
    InvalidType,
    OtherFailure,
}

enum class DeviceRuntimeValue {
    Enabled,
    Disabled,
    Unknown,
}

enum class DeviceRuntimeKillSwitchStatus {
    Enabled,
    NotEnabled,
    Unknown,
}

enum class DeviceRuntimeMemoryPressure {
    UiHidden,
    Background,
    Moderate,
    Critical,
    Unknown,
}

enum class DeviceRuntimeBackgroundSurvivalPhase {
    ScreenOffStarted,
    ScreenOffProbe,
    AfterWake,
}

enum class DeviceRuntimeBackgroundSurvivalOutcome {
    Pending,
    Passed,
    Failed,
    Inconclusive,
}

enum class DeviceRuntimeBackgroundSurvivalReason {
    TooShort,
    ServiceStopped,
    ServiceRestarted,
    ScreenOffProbeMissing,
    NoDataPlaneDelta,
    PostActionProbeFailed,
    TelemetryStale,
    ScreenStateChanged,
    Cancelled,
}

data class DeviceRuntimeDataPlaneCounters(
    val tunnelTxPackets: Long = 0,
    val tunnelTxBytes: Long = 0,
    val tunnelRxPackets: Long = 0,
    val tunnelRxBytes: Long = 0,
    val nativeTxPackets: Long = 0,
    val nativeTxBytes: Long = 0,
    val nativeRxPackets: Long = 0,
    val nativeRxBytes: Long = 0,
)

data class DeviceRuntimeDataPlaneDelta(
    val tunnelPackets: Long = 0,
    val tunnelBytes: Long = 0,
    val nativePackets: Long = 0,
    val nativeBytes: Long = 0,
) {
    val hasForwarding: Boolean
        get() = tunnelPackets > 0L || tunnelBytes > 0L || nativePackets > 0L || nativeBytes > 0L
}

fun DeviceRuntimeDataPlaneCounters.deltaFrom(before: DeviceRuntimeDataPlaneCounters): DeviceRuntimeDataPlaneDelta =
    DeviceRuntimeDataPlaneDelta(
        tunnelPackets =
            positiveDelta(tunnelTxPackets, before.tunnelTxPackets) +
                positiveDelta(tunnelRxPackets, before.tunnelRxPackets),
        tunnelBytes =
            positiveDelta(tunnelTxBytes, before.tunnelTxBytes) +
                positiveDelta(tunnelRxBytes, before.tunnelRxBytes),
        nativePackets =
            positiveDelta(nativeTxPackets, before.nativeTxPackets) +
                positiveDelta(nativeRxPackets, before.nativeRxPackets),
        nativeBytes =
            positiveDelta(nativeTxBytes, before.nativeTxBytes) +
                positiveDelta(nativeRxBytes, before.nativeRxBytes),
    )

sealed interface DeviceRuntimeEvidence {
    val observedAtMillis: Long

    data class ServiceLifecycle(
        val mode: Mode,
        val phase: DeviceRuntimeLifecyclePhase,
        override val observedAtMillis: Long,
    ) : DeviceRuntimeEvidence

    data class ForegroundCall(
        val mode: Mode,
        val kind: DeviceRuntimeForegroundCallKind,
        val outcome: DeviceRuntimeForegroundOutcome,
        override val observedAtMillis: Long,
        val serviceType: DeviceRuntimeForegroundServiceType = DeviceRuntimeForegroundServiceType.Unknown,
    ) : DeviceRuntimeEvidence

    data class VpnPolicy(
        val alwaysOn: DeviceRuntimeValue,
        val lockdown: DeviceRuntimeValue,
        val killSwitch: DeviceRuntimeKillSwitchStatus,
        override val observedAtMillis: Long,
    ) : DeviceRuntimeEvidence

    data class MemoryTrim(
        val pressure: DeviceRuntimeMemoryPressure,
        override val observedAtMillis: Long,
    ) : DeviceRuntimeEvidence

    data class BackgroundSurvival(
        val mode: Mode,
        val phase: DeviceRuntimeBackgroundSurvivalPhase,
        val outcome: DeviceRuntimeBackgroundSurvivalOutcome,
        val reason: DeviceRuntimeBackgroundSurvivalReason? = null,
        val screenOffDurationMs: Long? = null,
        val countersBefore: DeviceRuntimeDataPlaneCounters,
        val countersAfter: DeviceRuntimeDataPlaneCounters? = null,
        val counterDelta: DeviceRuntimeDataPlaneDelta? = null,
        override val observedAtMillis: Long,
    ) : DeviceRuntimeEvidence
}

private fun positiveDelta(
    after: Long,
    before: Long,
): Long = (after - before).coerceAtLeast(0L)

/** Process-local, bounded hand-off from Android callbacks to diagnostics. Exactly one collector is supported. */
interface DeviceRuntimeEvidenceStore {
    val events: Flow<DeviceRuntimeEvidence>
    val foregroundServiceState: StateFlow<DeviceRuntimeForegroundServiceState>

    fun record(event: DeviceRuntimeEvidence)
}

enum class DeviceRuntimeForegroundServiceType {
    SpecialUse,
    Unknown,
}

data class DeviceRuntimeForegroundServiceState(
    val active: Boolean = false,
    val mode: Mode? = null,
    val serviceType: DeviceRuntimeForegroundServiceType = DeviceRuntimeForegroundServiceType.Unknown,
)

@Singleton
class DefaultDeviceRuntimeEvidenceStore
    @Inject
    constructor() : DeviceRuntimeEvidenceStore {
        private val channel =
            Channel<DeviceRuntimeEvidence>(
                capacity = DeviceRuntimeEvidenceCapacity,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private val _foregroundServiceState = MutableStateFlow(DeviceRuntimeForegroundServiceState())

        override val events: Flow<DeviceRuntimeEvidence> = channel.receiveAsFlow()
        override val foregroundServiceState: StateFlow<DeviceRuntimeForegroundServiceState> =
            _foregroundServiceState.asStateFlow()

        override fun record(event: DeviceRuntimeEvidence) {
            updateForegroundServiceState(event)
            channel.trySend(event)
        }

        private fun updateForegroundServiceState(event: DeviceRuntimeEvidence) {
            when (event) {
                is DeviceRuntimeEvidence.ForegroundCall -> {
                    when {
                        event.outcome == DeviceRuntimeForegroundOutcome.Returned -> {
                            _foregroundServiceState.value =
                                DeviceRuntimeForegroundServiceState(
                                    active = true,
                                    mode = event.mode,
                                    serviceType = event.serviceType,
                                )
                        }

                        event.kind == DeviceRuntimeForegroundCallKind.Initial &&
                            _foregroundServiceState.value.mode == event.mode -> {
                            _foregroundServiceState.value = DeviceRuntimeForegroundServiceState()
                        }
                    }
                }

                is DeviceRuntimeEvidence.ServiceLifecycle -> {
                    if (event.phase == DeviceRuntimeLifecyclePhase.Destroyed &&
                        _foregroundServiceState.value.mode == event.mode
                    ) {
                        _foregroundServiceState.value = DeviceRuntimeForegroundServiceState()
                    }
                }

                else -> {
                    Unit
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceRuntimeEvidenceStoreModule {
    @Binds
    @Singleton
    abstract fun bindDeviceRuntimeEvidenceStore(store: DefaultDeviceRuntimeEvidenceStore): DeviceRuntimeEvidenceStore
}

internal const val DeviceRuntimeEvidenceCapacity = 64
