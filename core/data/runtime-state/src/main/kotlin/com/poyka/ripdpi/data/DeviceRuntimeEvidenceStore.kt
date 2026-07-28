package com.poyka.ripdpi.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
}

/** Process-local, bounded hand-off from Android callbacks to diagnostics. Exactly one collector is supported. */
interface DeviceRuntimeEvidenceStore {
    val events: Flow<DeviceRuntimeEvidence>

    fun record(event: DeviceRuntimeEvidence)
}

@Singleton
class DefaultDeviceRuntimeEvidenceStore
    @Inject
    constructor() : DeviceRuntimeEvidenceStore {
        private val channel =
            Channel<DeviceRuntimeEvidence>(
                capacity = DeviceRuntimeEvidenceCapacity,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val events: Flow<DeviceRuntimeEvidence> = channel.receiveAsFlow()

        override fun record(event: DeviceRuntimeEvidence) {
            channel.trySend(event)
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
