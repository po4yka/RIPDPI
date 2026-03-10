package com.poyka.ripdpi.services

import app.cash.turbine.test
import com.poyka.ripdpi.core.NativeRuntimeEvent
import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.core.TunnelStats
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateManagerTest {
    @Test
    fun `initial status is Halted VPN`() {
        val serviceStateStore = DefaultServiceStateStore()
        val (status, mode) = serviceStateStore.status.value
        assertEquals(AppStatus.Halted, status)
        assertEquals(Mode.VPN, mode)
    }

    @Test
    fun `setStatus updates status flow`() {
        val serviceStateStore = DefaultServiceStateStore()
        serviceStateStore.setStatus(AppStatus.Running, Mode.Proxy)
        val (status, mode) = serviceStateStore.status.value
        assertEquals(AppStatus.Running, status)
        assertEquals(Mode.Proxy, mode)
    }

    @Test
    fun `emitFailed sends event`() =
        runTest {
            val serviceStateStore = DefaultServiceStateStore()
            serviceStateStore.events.test {
                serviceStateStore.emitFailed(Sender.VPN)
                val event = awaitItem()
                assertTrue(event is ServiceEvent.Failed)
                assertEquals(Sender.VPN, (event as ServiceEvent.Failed).sender)
            }
        }

    @Test
    fun `emitFailed proxy sends correct sender`() =
        runTest {
            val serviceStateStore = DefaultServiceStateStore()
            serviceStateStore.events.test {
                serviceStateStore.emitFailed(Sender.Proxy)
                val event = awaitItem() as ServiceEvent.Failed
                assertEquals(Sender.Proxy, event.sender)
            }
        }

    @Test
    fun `updateTelemetry updates telemetry flow`() {
        val serviceStateStore = DefaultServiceStateStore()
        val snapshot =
            ServiceTelemetrySnapshot(
                mode = Mode.VPN,
                status = AppStatus.Running,
                updatedAt = 123L,
            )

        serviceStateStore.updateTelemetry(snapshot)

        assertEquals(snapshot, serviceStateStore.telemetry.value)
    }

    @Test
    fun `updateTelemetry preserves proxy and tunnel native payloads`() {
        val serviceStateStore = DefaultServiceStateStore()
        val snapshot =
            ServiceTelemetrySnapshot(
                mode = Mode.Proxy,
                status = AppStatus.Running,
                tunnelStats = TunnelStats(txPackets = 3, rxBytes = 9),
                proxyTelemetry =
                    NativeRuntimeSnapshot(
                        source = "proxy",
                        state = "running",
                        nativeEvents =
                            listOf(
                                NativeRuntimeEvent(
                                    source = "proxy",
                                    level = "info",
                                    message = "accepted",
                                    createdAt = 10L,
                                ),
                            ),
                    ),
                tunnelTelemetry =
                    NativeRuntimeSnapshot(
                        source = "tunnel",
                        state = "running",
                        nativeEvents =
                            listOf(
                                NativeRuntimeEvent(
                                    source = "tunnel",
                                    level = "warn",
                                    message = "slow upstream",
                                    createdAt = 20L,
                                ),
                            ),
                    ),
                updatedAt = 123L,
            )

        serviceStateStore.updateTelemetry(snapshot)

        val stored = serviceStateStore.telemetry.value
        assertEquals(Mode.Proxy, stored.mode)
        assertEquals(3L, stored.tunnelStats.txPackets)
        assertEquals("accepted", stored.proxyTelemetry.nativeEvents.single().message)
        assertEquals("slow upstream", stored.tunnelTelemetry.nativeEvents.single().message)
    }

    @Test
    fun `running transition records start time and increments restart count`() {
        val serviceStateStore = DefaultServiceStateStore()

        serviceStateStore.setStatus(AppStatus.Running, Mode.Proxy)
        val firstStart = serviceStateStore.telemetry.value.serviceStartedAt
        val firstRestartCount = serviceStateStore.telemetry.value.restartCount

        serviceStateStore.setStatus(AppStatus.Halted, Mode.Proxy)
        serviceStateStore.setStatus(AppStatus.Running, Mode.Proxy)
        val secondStart = serviceStateStore.telemetry.value.serviceStartedAt

        assertTrue(firstStart != null)
        assertTrue(secondStart != null)
        assertEquals(2, serviceStateStore.telemetry.value.restartCount)
        assertEquals(1, firstRestartCount)
    }

    @Test
    fun `emitFailed stores last failure metadata`() {
        val serviceStateStore = DefaultServiceStateStore()

        serviceStateStore.emitFailed(Sender.Proxy)

        assertEquals(Sender.Proxy, serviceStateStore.telemetry.value.lastFailureSender)
        assertTrue((serviceStateStore.telemetry.value.lastFailureAt ?: 0L) > 0L)
    }
}
