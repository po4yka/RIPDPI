package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ApplicationIoScope
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
    Samsung("samsung"),
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
    val foregroundNotificationChannels: NotificationChannelState,
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
                flushPendingLocked()
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
                    flushPendingLocked()
                    recordLocked(DeviceStateTrigger.Failure, provider.capture())
                } finally {
                    closeObservationAndClearState()
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
                } finally {
                    closeObservationAndClearState()
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
        ) {
            if ((trigger.singleton && trigger in recordedSingletonTriggers) || !hasCapacityFor(trigger)) return

            val event =
                PendingDeviceStateEvent(
                    trigger = trigger,
                    snapshot = snapshot,
                    mode = activeMode,
                    createdAt = clock.now(),
                )
            val connectionSessionId = activeConnectionSessionId
            val accepted =
                if (connectionSessionId == null) {
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

        private suspend fun flushPendingLocked() {
            val connectionSessionId = activeConnectionSessionId ?: return
            while (pendingEvents.isNotEmpty()) {
                persistLocked(pendingEvents.first(), connectionSessionId)
                pendingEvents.removeAt(0)
            }
        }

        private suspend fun persistLocked(
            event: PendingDeviceStateEvent,
            connectionSessionId: String,
        ) {
            if (!hasCapacityFor(event.trigger)) return
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
                val removableIndex = pendingEvents.indexOfFirst { it.trigger == DeviceStateTrigger.SystemStateChanged }
                if (removableIndex >= 0) {
                    pendingEvents.removeAt(removableIndex)
                } else {
                    return false
                }
            }
            pendingEvents += event
            return true
        }

        private fun hasCapacityFor(trigger: DeviceStateTrigger): Boolean =
            when (trigger) {
                DeviceStateTrigger.Failure -> persistedEventCount < MaxDeviceStateEvents - 1
                DeviceStateTrigger.Stop -> persistedEventCount < MaxDeviceStateEvents
                else -> persistedEventCount < MaxDeviceStateEvents - ReservedTerminalEvents
            }

        private fun resetSessionState(mode: Mode) {
            clearSessionState()
            activeMode = mode
        }

        private fun closeObservationAndClearState() {
            val activeObservation = observation
            observation = null
            try {
                activeObservation?.close()
            } finally {
                clearSessionState()
            }
        }

        private fun clearSessionState() {
            activeConnectionSessionId = null
            activeMode = null
            pendingEvents.clear()
            recordedSingletonTriggers.clear()
            lastSnapshot = null
            persistedEventCount = 0
        }
    }

private enum class DeviceStateTrigger(
    val wireValue: String,
    val singleton: Boolean,
) {
    ServiceStart("service_start", true),
    RunningReady("running_ready", true),
    SystemStateChanged("system_state_changed", false),
    ReconnectStart("reconnect_start", false),
    Failure("failure", true),
    Recovery("recovery", false),
    Handover("handover", false),
    Stop("stop", true),
}

private data class PendingDeviceStateEvent(
    val trigger: DeviceStateTrigger,
    val snapshot: DeviceStateSnapshot,
    val mode: Mode?,
    val createdAt: Long,
) {
    fun toCanonicalMessage(): String =
        buildString {
            append("event=device_state")
            append(" trigger=").append(trigger.wireValue)
            append(" screen_interactive=").append(snapshot.screenInteractive.wireValue)
            append(" device_idle=").append(snapshot.deviceIdle.wireValue)
            append(" power_saver=").append(snapshot.powerSaver.wireValue)
            append(" background_restricted=").append(snapshot.backgroundRestricted.wireValue)
            append(" battery_optimization_exempt=").append(snapshot.batteryOptimizationExempt.wireValue)
            append(" low_power_standby=").append(snapshot.lowPowerStandby.wireValue)
            append(" low_power_standby_exempt=").append(snapshot.lowPowerStandbyExempt.wireValue)
            append(" battery_level=").append(snapshot.batteryLevel.wireValue)
            append(" charging=").append(snapshot.charging.wireValue)
            append(" standby_bucket=").append(snapshot.standbyBucket.wireValue)
            append(" notification_permission=").append(snapshot.notificationPermission.wireValue)
            append(" notifications_allowed=").append(snapshot.notificationsAllowed.wireValue)
            append(" foreground_notification_channels=").append(snapshot.foregroundNotificationChannels.wireValue)
            append(" memory_pressure=").append(snapshot.memoryPressure.wireValue)
            append(" thermal_status=").append(snapshot.thermalStatus.wireValue)
            append(" manufacturer_family=").append(snapshot.manufacturerFamily.wireValue)
            append(" vendor_policy_visibility=unavailable")
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
private const val ReservedTerminalEvents = 2
