package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeForegroundOutcome
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Privacy-safe lifecycle hooks for connection-scoped Android device-state evidence. */
interface DeviceStateEventRecorder {
    suspend fun beginServiceStart(mode: Mode)

    suspend fun attachRunningSession(
        connectionSessionId: String,
        mode: Mode,
    )

    suspend fun recordFailure()

    suspend fun recordReconnectStart()

    suspend fun recordStandaloneFailure(
        connectionSessionId: String,
        mode: Mode,
    )

    suspend fun recordRecovery()

    suspend fun recordHandover()

    suspend fun recordStop()

    suspend fun recordRuntimeEvidence(event: DeviceRuntimeEvidence)
}

internal enum class DeviceStateValue(
    val wireValue: String,
) {
    Enabled("enabled"),
    Disabled("disabled"),
    Unknown("unknown"),
    NotRequired("not_required"),
    NotSupported("not_supported"),
}

internal enum class DeviceBatteryBand(
    val wireValue: String,
) {
    Empty("empty"),
    Critical("critical"),
    Low("low"),
    Medium("medium"),
    High("high"),
    Full("full"),
    Unknown("unknown"),
}

internal enum class DeviceStandbyBucket(
    val wireValue: String,
) {
    Active("active"),
    WorkingSet("working_set"),
    Frequent("frequent"),
    Rare("rare"),
    Restricted("restricted"),
    Unknown("unknown"),
    NotSupported("not_supported"),
}

internal enum class NotificationChannelState(
    val wireValue: String,
) {
    Enabled("enabled"),
    Blocked("blocked"),
    Partial("partial"),
    Missing("missing"),
    Unknown("unknown"),
    NotSupported("not_supported"),
}

internal enum class ForegroundServiceTypeBand(
    val wireValue: String,
) {
    None("none"),
    SpecialUse("special_use"),
    Other("other"),
    Unknown("unknown"),
    NotSupported("not_supported"),
}

internal enum class ProcessImportanceBand(
    val wireValue: String,
) {
    Foreground("foreground"),
    Visible("visible"),
    ForegroundService("foreground_service"),
    Service("service"),
    Background("background"),
    Cached("cached"),
    Gone("gone"),
    Unknown("unknown"),
}

internal enum class MemoryPressureBand(
    val wireValue: String,
) {
    None("none"),
    UiHidden("ui_hidden"),
    Background("background"),
    Moderate("moderate"),
    Critical("critical"),
    Unknown("unknown"),
}

internal enum class DeviceThermalBand(
    val wireValue: String,
) {
    None("none"),
    Light("light"),
    Moderate("moderate"),
    Severe("severe"),
    Critical("critical"),
    Emergency("emergency"),
    Shutdown("shutdown"),
    Unknown("unknown"),
    NotSupported("not_supported"),
}

internal enum class DeviceManufacturerFamily(
    val wireValue: String,
) {
    Other("other"),
    Unknown("unknown"),
}

internal data class DeviceStateSnapshot(
    val screenInteractive: DeviceStateValue,
    val deviceIdle: DeviceStateValue,
    val powerSaver: DeviceStateValue,
    val backgroundRestricted: DeviceStateValue,
    val batteryOptimizationExempt: DeviceStateValue,
    val lowPowerStandby: DeviceStateValue,
    val lowPowerStandbyExempt: DeviceStateValue,
    val batteryLevel: DeviceBatteryBand,
    val charging: DeviceStateValue,
    val standbyBucket: DeviceStandbyBucket,
    val notificationPermission: DeviceStateValue,
    val notificationsAllowed: DeviceStateValue,
    val notificationsPaused: DeviceStateValue,
    val foregroundNotificationActive: DeviceStateValue,
    val foregroundNotificationChannels: NotificationChannelState,
    val foregroundServiceType: ForegroundServiceTypeBand,
    val userUnlocked: DeviceStateValue,
    val processImportance: ProcessImportanceBand,
    val memoryPressure: MemoryPressureBand,
    val thermalStatus: DeviceThermalBand,
    val manufacturerFamily: DeviceManufacturerFamily,
)

internal fun interface DeviceStateObservation : AutoCloseable {
    override fun close()
}

internal interface DeviceStateProvider {
    fun capture(): DeviceStateSnapshot

    fun observeChanges(onChanged: () -> Unit): DeviceStateObservation
}

internal fun interface DeviceStateEventClock {
    fun now(): Long
}

@Singleton
internal class SystemDeviceStateEventClock
    @Inject
    constructor() : DeviceStateEventClock {
        override fun now(): Long = System.currentTimeMillis()
    }

@Singleton
internal class DefaultDeviceStateEventRecorder
    @Inject
    constructor(
        private val provider: DeviceStateProvider,
        private val artifactWriteStore: DiagnosticsArtifactWriteStore,
        private val clock: DeviceStateEventClock,
        @param:ApplicationIoScope private val scope: CoroutineScope,
    ) : DeviceStateEventRecorder {
        private val mutex = Mutex()
        private val pendingEvents = mutableListOf<PendingDeviceStateEvent>()
        private val recordedSingletonTriggers = mutableSetOf<DeviceStateTrigger>()

        private var activeConnectionSessionId: String? = null
        private var activeMode: Mode? = null
        private var observation: DeviceStateObservation? = null
        private var lastSnapshot: DeviceStateSnapshot? = null
        private var persistedEventCount = 0
        private var lastEventCreatedAt = 0L
        private val terminalConnectionSessionIds = mutableMapOf<Mode, String>()

        override suspend fun beginServiceStart(mode: Mode) {
            mutex.withLock {
                if (observation != null && activeConnectionSessionId == null) {
                    return
                }
                observation?.close()
                resetSessionState(mode)
                observation = provider.observeChanges(::scheduleSystemStateCapture)
                recordLocked(DeviceStateTrigger.ServiceStart, provider.capture())
            }
        }

        override suspend fun attachRunningSession(
            connectionSessionId: String,
            mode: Mode,
        ) {
            require(connectionSessionId.isNotBlank())
            mutex.withLock {
                if (observation == null) {
                    resetSessionState(mode)
                    observation = provider.observeChanges(::scheduleSystemStateCapture)
                    recordLocked(DeviceStateTrigger.ServiceStart, provider.capture())
                }
                activeConnectionSessionId = connectionSessionId
                activeMode = mode
                flushPendingLocked(connectionSessionId)
                recordLocked(DeviceStateTrigger.RunningReady, provider.capture())
            }
        }

        override suspend fun recordFailure() {
            recordLifecycle(DeviceStateTrigger.Failure)
        }

        override suspend fun recordReconnectStart() {
            recordLifecycle(DeviceStateTrigger.ReconnectStart)
        }

        override suspend fun recordStandaloneFailure(
            connectionSessionId: String,
            mode: Mode,
        ) {
            require(connectionSessionId.isNotBlank())
            mutex.withLock {
                try {
                    if (observation == null) {
                        resetSessionState(mode)
                    }
                    activeConnectionSessionId = connectionSessionId
                    activeMode = mode
                    flushPendingLocked(connectionSessionId)
                    recordLocked(DeviceStateTrigger.Failure, provider.capture())
                } finally {
                    closeObservationAndClearState(retainTerminalState = true)
                }
            }
        }

        override suspend fun recordRecovery() {
            recordLifecycle(DeviceStateTrigger.Recovery)
        }

        override suspend fun recordHandover() {
            recordLifecycle(DeviceStateTrigger.Handover)
        }

        override suspend fun recordStop() {
            mutex.withLock {
                if (observation == null && activeConnectionSessionId == null && pendingEvents.isEmpty()) {
                    return
                }
                try {
                    recordLocked(DeviceStateTrigger.Stop, provider.capture())
                    if (activeConnectionSessionId == null) {
                        flushPendingLocked(connectionSessionId = null)
                    }
                } finally {
                    closeObservationAndClearState(retainTerminalState = true)
                }
            }
        }

        override suspend fun recordRuntimeEvidence(event: DeviceRuntimeEvidence) {
            mutex.withLock {
                val trigger = event.toTrigger()
                val mode = event.modeOrNull()
                if (event.startsServiceContext() && observation == null && activeConnectionSessionId == null) {
                    resetSessionState(checkNotNull(mode))
                    observation = provider.observeChanges(::scheduleSystemStateCapture)
                }
                val hasContext = observation != null || activeConnectionSessionId != null || pendingEvents.isNotEmpty()
                val hasTerminalContext = mode != null && terminalConnectionSessionIds.containsKey(mode)
                if (!hasContext && !hasTerminalContext && !event.flushesWithoutSession()) return

                recordLocked(
                    trigger = trigger,
                    snapshot = provider.capture(),
                    runtimeEvidence = event,
                    createdAt = event.observedAtMillis,
                )
                val destroyedMode =
                    (event as? DeviceRuntimeEvidence.ServiceLifecycle)
                        ?.takeIf { lifecycle -> lifecycle.phase == DeviceRuntimeLifecyclePhase.Destroyed }
                        ?.mode
                if (event.flushesWithoutSession() && activeConnectionSessionId == null) {
                    if (!hasTerminalContext) {
                        flushPendingLocked(connectionSessionId = null)
                    }
                    destroyedMode?.let(terminalConnectionSessionIds::remove)
                    closeObservationAndClearState()
                } else if (destroyedMode != null) {
                    terminalConnectionSessionIds.remove(destroyedMode)
                }
            }
        }

        private suspend fun recordLifecycle(trigger: DeviceStateTrigger) {
            mutex.withLock {
                if (observation == null && activeConnectionSessionId == null && pendingEvents.isEmpty()) {
                    return
                }
                recordLocked(trigger, provider.capture())
            }
        }

        private fun scheduleSystemStateCapture() {
            scope.launch {
                mutex.withLock {
                    if (observation == null) return@withLock
                    val snapshot = provider.capture()
                    if (snapshot == lastSnapshot) return@withLock
                    recordLocked(DeviceStateTrigger.SystemStateChanged, snapshot)
                }
            }
        }

        private suspend fun recordLocked(
            trigger: DeviceStateTrigger,
            snapshot: DeviceStateSnapshot,
            runtimeEvidence: DeviceRuntimeEvidence? = null,
            createdAt: Long = clock.now(),
        ) {
            if (
                (trigger.singleton && trigger in recordedSingletonTriggers) ||
                !hasCapacityFor(trigger, runtimeEvidence)
            ) {
                return
            }

            val normalizedCreatedAt = maxOf(createdAt.coerceAtLeast(0L), lastEventCreatedAt + 1L)
            lastEventCreatedAt = normalizedCreatedAt

            val event =
                PendingDeviceStateEvent(
                    trigger = trigger,
                    snapshot = snapshot,
                    mode = runtimeEvidence?.modeOrNull() ?: activeMode,
                    createdAt = normalizedCreatedAt,
                    runtimeEvidence = runtimeEvidence,
                )
            val evidenceMode = runtimeEvidence?.modeOrNull()
            val connectionSessionId =
                activeConnectionSessionId.takeIf { evidenceMode == null || evidenceMode == activeMode }
                    ?: evidenceMode?.let { mode -> terminalConnectionSessionIds[mode] }.takeIf {
                        trigger.isTerminalRuntime
                    }
            val accepted =
                if (connectionSessionId == null && !trigger.isTerminalRuntime) {
                    addPendingEvent(event)
                } else {
                    persistLocked(event, connectionSessionId)
                    true
                }
            if (accepted) {
                lastSnapshot = snapshot
                if (trigger.singleton) {
                    recordedSingletonTriggers += trigger
                }
            }
        }

        private suspend fun flushPendingLocked(connectionSessionId: String?) {
            while (pendingEvents.isNotEmpty()) {
                persistLocked(pendingEvents.first(), connectionSessionId)
                pendingEvents.removeAt(0)
            }
        }

        private suspend fun persistLocked(
            event: PendingDeviceStateEvent,
            connectionSessionId: String?,
        ) {
            if (!hasCapacityFor(event.trigger, event.runtimeEvidence)) return
            artifactWriteStore.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = null,
                    connectionSessionId = connectionSessionId,
                    source = DeviceStateEventSource,
                    level = "info",
                    message = event.toCanonicalMessage(),
                    createdAt = event.createdAt,
                    mode = (event.mode ?: activeMode)?.preferenceValue,
                    subsystem = DeviceStateEventSubsystem,
                ),
            )
            persistedEventCount += 1
        }

        private fun addPendingEvent(event: PendingDeviceStateEvent): Boolean {
            if (pendingEvents.size >= MaxPendingDeviceStateEvents) {
                val removableIndex =
                    pendingEvents.indexOfFirst {
                        it.trigger == DeviceStateTrigger.SystemStateChanged ||
                            it.trigger == DeviceStateTrigger.ServiceStartCommand
                    }
                if (removableIndex >= 0) {
                    pendingEvents.removeAt(removableIndex)
                } else {
                    return false
                }
            }
            pendingEvents += event
            return true
        }

        private fun hasCapacityFor(
            trigger: DeviceStateTrigger,
            runtimeEvidence: DeviceRuntimeEvidence?,
        ): Boolean =
            when (trigger) {
                DeviceStateTrigger.ServiceDestroyed -> {
                    persistedEventCount < MaxDeviceStateEvents
                }

                DeviceStateTrigger.Stop -> {
                    persistedEventCount < MaxDeviceStateEvents - 1
                }

                DeviceStateTrigger.Failure -> {
                    persistedEventCount < MaxDeviceStateEvents - 2
                }

                DeviceStateTrigger.ForegroundCall -> {
                    if ((runtimeEvidence as? DeviceRuntimeEvidence.ForegroundCall)?.outcome !=
                        DeviceRuntimeForegroundOutcome.Returned
                    ) {
                        persistedEventCount < MaxDeviceStateEvents - ForegroundFailureTerminalReserve
                    } else {
                        persistedEventCount < MaxDeviceStateEvents - ReservedTerminalEvents
                    }
                }

                else -> {
                    persistedEventCount < MaxDeviceStateEvents - ReservedTerminalEvents
                }
            }

        private fun resetSessionState(mode: Mode) {
            clearSessionState()
            activeMode = mode
        }

        private fun closeObservationAndClearState(retainTerminalState: Boolean = false) {
            if (retainTerminalState) {
                val mode = activeMode
                val connectionSessionId = activeConnectionSessionId
                if (mode != null && connectionSessionId != null) {
                    terminalConnectionSessionIds[mode] = connectionSessionId
                }
            }
            val activeObservation = observation
            observation = null
            try {
                activeObservation?.close()
            } finally {
                if (retainTerminalState) {
                    activeConnectionSessionId = null
                    activeMode = null
                    pendingEvents.clear()
                    recordedSingletonTriggers.clear()
                    lastSnapshot = null
                } else {
                    clearSessionState()
                }
            }
        }

        private fun clearSessionState() {
            activeConnectionSessionId = null
            activeMode = null
            pendingEvents.clear()
            recordedSingletonTriggers.clear()
            lastSnapshot = null
            persistedEventCount = 0
            lastEventCreatedAt = 0L
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DeviceStateEventRecorderModule {
    @Binds
    @Singleton
    abstract fun bindDeviceStateProvider(provider: AndroidDeviceStateProvider): DeviceStateProvider

    @Binds
    @Singleton
    abstract fun bindDeviceStateEventClock(clock: SystemDeviceStateEventClock): DeviceStateEventClock

    @Binds
    @Singleton
    abstract fun bindDeviceStateEventRecorder(recorder: DefaultDeviceStateEventRecorder): DeviceStateEventRecorder
}

private const val DeviceStateEventSource = "android_device_state"
private const val DeviceStateEventSubsystem = "device_state"
private const val MaxPendingDeviceStateEvents = 16
private const val MaxDeviceStateEvents = 64
private const val ReservedTerminalEvents = 4
private const val ForegroundFailureTerminalReserve = 3
