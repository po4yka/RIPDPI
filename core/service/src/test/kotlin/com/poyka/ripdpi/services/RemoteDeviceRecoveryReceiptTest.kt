package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class RemoteDeviceRecoveryReceiptTest {
    @Test
    fun `cold recovery records categorical milestones without active probing`() {
        var elapsed = 1_000L
        val collector = collector(elapsed = { elapsed })
        val generation = collector.beginStart(action = null, serviceInstanceId = "instance-a")

        elapsed = 1_500L
        collector.recordForegroundService(
            generation = generation,
            userUnlocked = true,
            policy = AndroidHardKillSwitchStateReader.fromPlatformFlags(alwaysOn = true, lockdown = true),
        )
        elapsed = 2_500L
        collector.recordTunReady("instance-a")
        elapsed = 7_500L
        collector.recordTunnelTelemetry(
            "instance-a",
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                tunnelStats = TunnelStats(txPackets = 1L, rxPackets = 1L),
            ),
        )

        val receipt = collector.snapshot()
        assertEquals("sticky_redelivery", receipt.startOrigin)
        assertEquals("enabled", receipt.userUnlocked)
        assertEquals("enabled", receipt.alwaysOn)
        assertEquals("enabled", receipt.lockdown)
        assertEquals("under_1s", receipt.timeToForegroundService)
        assertEquals("1_to_5s", receipt.timeToTun)
        assertEquals("5_to_10s", receipt.timeToFirstFlow)
        assertEquals("bidirectional_observed", receipt.postStartDataPlaneOutcome)
    }

    @Test
    fun `new process recovery receives a fresh random generation and explicit unknown instance comparison`() {
        val firstProcess = collector(generation = { UUID.randomUUID().toString() })
        val secondProcess = collector(generation = { UUID.randomUUID().toString() })

        val firstGeneration = firstProcess.beginStart(null, "process-a-service")
        val secondGeneration = secondProcess.beginStart(null, "process-b-service")

        assertNotEquals(firstGeneration, secondGeneration)
        assertEquals("unknown", secondProcess.snapshot().serviceInstanceChanged)
        assertEquals("sticky_redelivery", secondProcess.snapshot().startOrigin)
    }

    @Test
    fun `same-process service restart changes generation and rejects stale cancellation`() {
        val generations = ArrayDeque(listOf("generation-a", "generation-b"))
        val collector = collector(generation = { generations.removeFirst() })
        val first = collector.beginStart("unknown-action", "instance-a")
        val second = collector.beginStart(VpnService.SERVICE_INTERFACE, "instance-b")

        collector.cancelServiceInstance("instance-a")

        val receipt = collector.snapshot()
        assertEquals("generation-a", first)
        assertEquals("generation-b", second)
        assertEquals("enabled", receipt.serviceInstanceChanged)
        assertEquals("always_on_or_boot", receipt.startOrigin)
        assertEquals("pending", receipt.postStartDataPlaneOutcome)
        assertFalse(isRecoveryReceiptStartAction(com.poyka.ripdpi.data.stopAction))
        assertFalse(isRecoveryReceiptStartAction(notificationStopAction))
    }

    @Test
    fun `current service cancellation is terminal only before observed data plane`() {
        val collector = collector()
        collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-a")
        collector.cancelServiceInstance("instance-a")
        assertEquals("cancelled", collector.snapshot().postStartDataPlaneOutcome)

        collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-b")
        collector.recordTunnelTelemetry(
            "instance-b",
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(txBytes = 1L)),
        )
        collector.cancelServiceInstance("instance-b")
        assertEquals("outbound_only", collector.snapshot().postStartDataPlaneOutcome)
    }

    @Test
    fun `redacted report rejects hostile receipt strings`() {
        val rendered =
            renderRemoteDeviceAcceptanceReport(
                RemoteDeviceAcceptanceReport(
                    device = RemoteDeviceAcceptanceDevice("unknown", "unknown", 0, "unknown"),
                    recoveryReceipt =
                        RemoteDeviceRecoveryReceipt(
                            generation = "uid=10123",
                            startOrigin = "socket=secret",
                            userUnlocked = "ip=192.0.2.1",
                            alwaysOn = "interface=wlan0",
                            lockdown = "kernel=6.1.2-private",
                        ),
                ),
            )

        listOf("10123", "secret", "192.0.2.1", "wlan0", "6.1.2-private").forEach { forbidden ->
            assertFalse(rendered.contains(forbidden))
        }
    }

    private fun collector(
        elapsed: () -> Long = { 1_000L },
        generation: () -> String = { "generation" },
    ): RemoteDeviceRecoveryReceiptCollector =
        RemoteDeviceRecoveryReceiptCollector(
            elapsedRealtime = elapsed,
            generationFactory = generation,
        )
}
