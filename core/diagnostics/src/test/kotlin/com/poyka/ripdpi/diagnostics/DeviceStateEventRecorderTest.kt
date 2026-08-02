package com.poyka.ripdpi.diagnostics

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.PowerManager
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneCounters
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneDelta
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.DeviceRuntimeForegroundCallKind
import com.poyka.ripdpi.data.DeviceRuntimeForegroundOutcome
import com.poyka.ripdpi.data.DeviceRuntimeForegroundServiceType
import com.poyka.ripdpi.data.DeviceRuntimeKillSwitchStatus
import com.poyka.ripdpi.data.DeviceRuntimeLifecyclePhase
import com.poyka.ripdpi.data.DeviceRuntimeMemoryPressure
import com.poyka.ripdpi.data.DeviceRuntimeValue
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceStateEventRecorderTest {
    @Test
    fun `buffers start transitions until session exists and deduplicates unchanged broadcasts`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val provider = FakeDeviceStateProvider(manufacturerSnapshot(DeviceManufacturerFamily.Other))
            val recorder = recorder(provider, stores)

            recorder.beginServiceStart(Mode.VPN)
            provider.emitChanged()
            runCurrent()
            assertTrue(stores.nativeEventsState.value.isEmpty())

            provider.snapshot = provider.snapshot.copy(screenInteractive = DeviceStateValue.Disabled)
            provider.emitChanged()
            runCurrent()
            recorder.attachRunningSession("connection-1", Mode.VPN)

            val attachedEvents = stores.nativeEventsState.value
            assertEquals(3, attachedEvents.size)
            assertTrue(attachedEvents[0].message.contains("trigger=service_start"))
            assertTrue(attachedEvents[1].message.contains("trigger=system_state_changed"))
            assertTrue(attachedEvents[2].message.contains("trigger=running_ready"))
            assertTrue(attachedEvents.all { it.connectionSessionId == "connection-1" })
            assertTrue(attachedEvents.all { it.mode == "vpn" })
            assertTrue(attachedEvents.all { it.message.contains("manufacturer_family=other") })

            provider.emitChanged()
            runCurrent()
            assertEquals(3, stores.nativeEventsState.value.size)
        }

    @Test
    fun `bounds transition events while retaining failure and stop evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val provider = FakeDeviceStateProvider()
            val recorder = recorder(provider, stores)

            recorder.beginServiceStart(Mode.VPN)
            recorder.attachRunningSession("connection-2", Mode.VPN)
            repeat(100) { index ->
                provider.snapshot =
                    provider.snapshot.copy(
                        screenInteractive =
                            if (index % 2 == 0) DeviceStateValue.Disabled else DeviceStateValue.Enabled,
                    )
                provider.emitChanged()
                runCurrent()
            }
            recorder.recordFailure()
            recorder.recordStop()

            val events = stores.nativeEventsState.value
            assertTrue(events.size <= 64)
            assertTrue(events.any { it.message.contains("trigger=failure") })
            assertTrue(events.any { it.message.contains("trigger=stop") })
            assertTrue(events.first().message.contains("trigger=service_start"))
        }

    @Test
    fun `correlates service foreground policy and trim evidence with running session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val recorder = recorder(FakeDeviceStateProvider(), stores)

            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.ServiceLifecycle(Mode.VPN, DeviceRuntimeLifecyclePhase.Created, 10L),
            )
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.ForegroundCall(
                    Mode.VPN,
                    DeviceRuntimeForegroundCallKind.Initial,
                    DeviceRuntimeForegroundOutcome.Returned,
                    11L,
                ),
            )
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.VpnPolicy(
                    DeviceRuntimeValue.Enabled,
                    DeviceRuntimeValue.Enabled,
                    DeviceRuntimeKillSwitchStatus.Enabled,
                    12L,
                ),
            )
            recorder.attachRunningSession("connection-runtime", Mode.VPN)
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.MemoryTrim(DeviceRuntimeMemoryPressure.Background, 13L),
            )

            val events = stores.nativeEventsState.value
            assertTrue(events.all { it.connectionSessionId == "connection-runtime" })
            assertTrue(events.any { it.message.contains("service_lifecycle=created") })
            assertTrue(events.any { it.message.contains("foreground_outcome=returned") })
            assertTrue(events.any { it.message.contains("vpn_lockdown=enabled") })
            assertTrue(events.any { it.message.contains("memory_trim_callback=background") })
        }

    @Test
    fun `correlates remote acceptance background survival evidence with running session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val recorder = recorder(FakeDeviceStateProvider(), stores)
            recorder.beginServiceStart(Mode.VPN)
            recorder.attachRunningSession("connection-background", Mode.VPN)

            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.BackgroundSurvival(
                    mode = Mode.VPN,
                    phase = DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Pending,
                    countersBefore = DeviceRuntimeDataPlaneCounters(tunnelTxBytes = 10L),
                    observedAtMillis = 20L,
                ),
            )
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.BackgroundSurvival(
                    mode = Mode.VPN,
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                    screenOffDurationMs = 300_000L,
                    countersBefore = DeviceRuntimeDataPlaneCounters(tunnelTxBytes = 10L),
                    countersAfter = DeviceRuntimeDataPlaneCounters(tunnelTxBytes = 42L),
                    counterDelta = DeviceRuntimeDataPlaneDelta(tunnelBytes = 32L),
                    observedAtMillis = 21L,
                ),
            )

            val backgroundEvents =
                stores.nativeEventsState.value.filter {
                    it.message.contains("trigger=remote_acceptance_background")
                }
            assertEquals(2, backgroundEvents.size)
            assertTrue(backgroundEvents.all { it.connectionSessionId == "connection-background" })
            assertTrue(backgroundEvents[0].message.contains("remote_acceptance_background_phase=screen_off_started"))
            assertTrue(backgroundEvents[0].message.contains("remote_acceptance_background_outcome=pending"))
            assertTrue(backgroundEvents[1].message.contains("remote_acceptance_background_phase=after_wake"))
            assertTrue(backgroundEvents[1].message.contains("remote_acceptance_background_outcome=passed"))
            assertTrue(backgroundEvents[1].message.contains("remote_acceptance_screen_off_ms=300000"))
            assertTrue(backgroundEvents[1].message.contains("remote_acceptance_delta_tunnel_bytes=32"))
            listOf("ssid", "bssid", "serial", "endpoint", "profile", "uuid").forEach { forbidden ->
                assertFalse(backgroundEvents.any { it.message.contains(forbidden, ignoreCase = true) })
            }
        }

    @Test
    fun `late service destroy remains correlated after logical stop`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val recorder = recorder(FakeDeviceStateProvider(), stores)
            recorder.beginServiceStart(Mode.Proxy)
            recorder.attachRunningSession("connection-terminal", Mode.Proxy)

            recorder.recordStop()
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.ServiceLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.Destroyed, 50L),
            )

            val destroyed = stores.nativeEventsState.value.single { it.message.contains("trigger=service_destroyed") }
            assertEquals("connection-terminal", destroyed.connectionSessionId)
            assertTrue(destroyed.message.contains("service_lifecycle=destroyed"))
        }

    @Test
    fun `late destroy from old mode never contaminates current session`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val recorder = recorder(FakeDeviceStateProvider(), stores)
            recorder.beginServiceStart(Mode.Proxy)
            recorder.attachRunningSession("connection-old", Mode.Proxy)
            recorder.recordStop()
            recorder.beginServiceStart(Mode.VPN)
            recorder.attachRunningSession("connection-current", Mode.VPN)

            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.ServiceLifecycle(Mode.Proxy, DeviceRuntimeLifecyclePhase.Destroyed, 50L),
            )
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.MemoryTrim(DeviceRuntimeMemoryPressure.Background, 51L),
            )

            val destroyed = stores.nativeEventsState.value.single { it.message.contains("trigger=service_destroyed") }
            val memoryTrim = stores.nativeEventsState.value.single { it.message.contains("trigger=memory_trim") }
            assertEquals("connection-old", destroyed.connectionSessionId)
            assertEquals("connection-current", memoryTrim.connectionSessionId)
        }

    @Test
    fun `early foreground failure flushes privacy safe global evidence`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val recorder = recorder(FakeDeviceStateProvider(), stores)
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.ServiceLifecycle(Mode.VPN, DeviceRuntimeLifecyclePhase.Created, 20L),
            )
            recorder.recordRuntimeEvidence(
                DeviceRuntimeEvidence.ForegroundCall(
                    Mode.VPN,
                    DeviceRuntimeForegroundCallKind.Initial,
                    DeviceRuntimeForegroundOutcome.SecurityRejected,
                    21L,
                ),
            )

            val events = stores.nativeEventsState.value
            assertEquals(2, events.size)
            assertTrue(events.all { it.connectionSessionId == null })
            assertTrue(events.last().message.contains("foreground_outcome=security_rejected"))
            listOf("serial", "ssid", "host", "exception").forEach { forbidden ->
                assertFalse(events.any { it.message.contains(forbidden, ignoreCase = true) })
            }
        }

    @Test
    fun `stop preserves earlier state transitions instead of replacing them`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val provider = FakeDeviceStateProvider()
            val recorder = recorder(provider, stores)

            recorder.beginServiceStart(Mode.Proxy)
            recorder.attachRunningSession("connection-3", Mode.Proxy)
            provider.snapshot = provider.snapshot.copy(deviceIdle = DeviceStateValue.Enabled)
            provider.emitChanged()
            runCurrent()
            recorder.recordStop()

            val messages = stores.nativeEventsState.value.map { it.message }
            assertEquals(4, messages.size)
            assertTrue(messages[2].contains("device_idle=enabled"))
            assertTrue(messages[3].contains("trigger=stop"))
            assertTrue(messages[3].contains("device_idle=enabled"))
        }

    @Test
    fun `API fallbacks and coarse bands remain categorical`() {
        @Suppress("DEPRECATION")
        val snapshot =
            buildDeviceStateSnapshot(
                apiLevel = Build.VERSION_CODES.O_MR1,
                screenInteractive = true,
                deviceIdle = false,
                powerSaver = false,
                backgroundRestricted = true,
                batteryOptimizationExempt = true,
                lowPowerStandby = true,
                lowPowerStandbyExempt = false,
                thermalStatus = PowerManager.THERMAL_STATUS_CRITICAL,
                batteryLevel = 9,
                batteryScale = 100,
                charging = true,
                standbyBucket = UsageStatsManager.STANDBY_BUCKET_RARE,
                notificationPermissionGranted = true,
                notificationsAllowed = false,
                notificationsPaused = true,
                foregroundNotificationActive = true,
                foregroundNotificationChannelState = NotificationChannelState.Enabled,
                foregroundServiceType = DeviceRuntimeForegroundServiceType.SpecialUse,
                userUnlocked = true,
                processImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
                lastTrimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                manufacturer = "Google",
            )

        assertEquals(DeviceStateValue.NotSupported, snapshot.backgroundRestricted)
        assertEquals(DeviceThermalBand.NotSupported, snapshot.thermalStatus)
        assertEquals(DeviceStateValue.NotSupported, snapshot.lowPowerStandby)
        assertEquals(DeviceStateValue.NotSupported, snapshot.lowPowerStandbyExempt)
        assertEquals(DeviceStandbyBucket.NotSupported, snapshot.standbyBucket)
        assertEquals(DeviceStateValue.NotRequired, snapshot.notificationPermission)
        assertEquals(DeviceStateValue.Disabled, snapshot.notificationsAllowed)
        assertEquals(DeviceStateValue.NotSupported, snapshot.notificationsPaused)
        assertEquals(DeviceStateValue.Enabled, snapshot.foregroundNotificationActive)
        assertEquals(ForegroundServiceTypeBand.NotSupported, snapshot.foregroundServiceType)
        assertEquals(DeviceStateValue.Enabled, snapshot.userUnlocked)
        assertEquals(ProcessImportanceBand.ForegroundService, snapshot.processImportance)
        assertEquals(DeviceBatteryBand.Critical, snapshot.batteryLevel)
        assertEquals(DeviceStateValue.Enabled, snapshot.charging)
        assertEquals(MemoryPressureBand.Background, snapshot.memoryPressure)
        assertEquals(DeviceManufacturerFamily.Other, snapshot.manufacturerFamily)
    }

    @Test
    fun `hostile platform strings cannot enter canonical event messages`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val provider =
                FakeDeviceStateProvider(
                    buildDeviceStateSnapshot(
                        apiLevel = Build.VERSION_CODES.Q,
                        screenInteractive = null,
                        deviceIdle = null,
                        powerSaver = null,
                        backgroundRestricted = null,
                        batteryOptimizationExempt = null,
                        lowPowerStandby = null,
                        lowPowerStandbyExempt = null,
                        thermalStatus = Int.MAX_VALUE,
                        batteryLevel = null,
                        batteryScale = null,
                        charging = null,
                        standbyBucket = Int.MAX_VALUE,
                        notificationPermissionGranted = null,
                        notificationsAllowed = null,
                        notificationsPaused = null,
                        foregroundNotificationActive = null,
                        foregroundNotificationChannelState = NotificationChannelState.Unknown,
                        foregroundServiceType = DeviceRuntimeForegroundServiceType.Unknown,
                        userUnlocked = null,
                        processImportance = Int.MAX_VALUE,
                        lastTrimLevel = Int.MAX_VALUE,
                        manufacturer = "Google serial=secret ssid=private ip=192.0.2.1 host=bad.example",
                    ),
                )
            val recorder = recorder(provider, stores)

            recorder.beginServiceStart(Mode.VPN)
            recorder.attachRunningSession("connection-4", Mode.VPN)

            val messages = stores.nativeEventsState.value.map { it.message }
            assertTrue(messages.all { it.contains("manufacturer_family=other") })
            assertTrue(messages.all { it.contains("notifications_paused=unknown") })
            assertTrue(messages.all { it.contains("foreground_notification_active=unknown") })
            assertTrue(messages.all { it.contains("foreground_service_type=unknown") })
            assertTrue(messages.all { it.contains("user_unlocked=unknown") })
            assertTrue(messages.all { it.contains("process_importance=cached") })
            listOf("secret", "ssid", "192.0.2.1", "bad.example", "serial").forEach { forbidden ->
                assertFalse(messages.any { it.contains(forbidden, ignoreCase = true) })
            }
            assertTrue(messages.all { it.split(' ').all { field -> field.count { char -> char == '=' } == 1 } })
        }

    @Test
    fun `failed singleton persistence can retry and failed stop always closes observation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val provider = FakeDeviceStateProvider()
            val recorder = recorder(provider, stores)
            recorder.beginServiceStart(Mode.VPN)
            recorder.attachRunningSession("connection-retry", Mode.VPN)

            var failFailure = true
            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message.contains("trigger=failure") && failFailure) {
                    failFailure = false
                    error("injected failure write")
                }
            }
            runCatching { recorder.recordFailure() }
            recorder.recordFailure()
            assertEquals(
                1,
                stores.nativeEventsState.value.count { it.message.contains("trigger=failure") },
            )

            stores.beforeInsertNativeSessionEvent = { event ->
                if (event.message.contains("trigger=stop")) error("injected stop write")
            }
            runCatching { recorder.recordStop() }
            assertFalse(provider.isObserving)

            val eventCount = stores.nativeEventsState.value.size
            provider.snapshot = provider.snapshot.copy(deviceIdle = DeviceStateValue.Enabled)
            provider.emitChanged()
            runCurrent()
            assertEquals(eventCount, stores.nativeEventsState.value.size)
        }

    private fun kotlinx.coroutines.test.TestScope.recorder(
        provider: FakeDeviceStateProvider,
        stores: FakeDiagnosticsHistoryStores,
    ): DefaultDeviceStateEventRecorder =
        DefaultDeviceStateEventRecorder(
            provider = provider,
            artifactWriteStore = stores,
            clock = TestDeviceStateEventClock(),
            scope = this,
        )

    private fun manufacturerSnapshot(family: DeviceManufacturerFamily): DeviceStateSnapshot =
        deviceStateSnapshotForTest(manufacturerFamily = family)
}
