package com.poyka.ripdpi.diagnostics

import android.app.usage.UsageStatsManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.PowerManager
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
            val provider = FakeDeviceStateProvider(manufacturerSnapshot(DeviceManufacturerFamily.Samsung))
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
            assertTrue(attachedEvents.all { it.message.contains("manufacturer_family=samsung") })

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
            assertEquals(64, events.size)
            assertTrue(events[events.lastIndex - 1].message.contains("trigger=failure"))
            assertTrue(events.last().message.contains("trigger=stop"))
            assertTrue(events.first().message.contains("trigger=service_start"))
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
                foregroundNotificationChannelState = NotificationChannelState.Enabled,
                lastTrimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                manufacturer = "SAMSUNG",
            )

        assertEquals(DeviceStateValue.NotSupported, snapshot.backgroundRestricted)
        assertEquals(DeviceThermalBand.NotSupported, snapshot.thermalStatus)
        assertEquals(DeviceStateValue.NotSupported, snapshot.lowPowerStandby)
        assertEquals(DeviceStateValue.NotSupported, snapshot.lowPowerStandbyExempt)
        assertEquals(DeviceStandbyBucket.NotSupported, snapshot.standbyBucket)
        assertEquals(DeviceStateValue.NotRequired, snapshot.notificationPermission)
        assertEquals(DeviceStateValue.Disabled, snapshot.notificationsAllowed)
        assertEquals(DeviceBatteryBand.Critical, snapshot.batteryLevel)
        assertEquals(DeviceStateValue.Enabled, snapshot.charging)
        assertEquals(MemoryPressureBand.Background, snapshot.memoryPressure)
        assertEquals(DeviceManufacturerFamily.Samsung, snapshot.manufacturerFamily)
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
                        foregroundNotificationChannelState = NotificationChannelState.Unknown,
                        lastTrimLevel = Int.MAX_VALUE,
                        manufacturer = "Samsung serial=secret ssid=private ip=192.0.2.1 host=bad.example",
                    ),
                )
            val recorder = recorder(provider, stores)

            recorder.beginServiceStart(Mode.VPN)
            recorder.attachRunningSession("connection-4", Mode.VPN)

            val messages = stores.nativeEventsState.value.map { it.message }
            assertTrue(messages.all { it.contains("manufacturer_family=other") })
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
