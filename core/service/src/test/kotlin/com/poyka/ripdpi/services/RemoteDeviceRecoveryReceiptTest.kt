package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        collector.recordTunReady(generation)
        elapsed = 7_500L
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                tunnelStats = TunnelStats(txPackets = 11L, rxPackets = 11L),
            ),
        )

        val receipt = collector.snapshot()
        assertEquals("process_death", receipt.startOrigin)
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
        assertEquals("process_death", secondProcess.snapshot().startOrigin)
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
        assertEquals("always_on", receipt.startOrigin)
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

        val generation = collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-b")
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(txBytes = 10L)),
        )
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(txBytes = 11L)),
        )
        collector.cancelServiceInstance("instance-b")
        assertEquals("outbound_only", collector.snapshot().postStartDataPlaneOutcome)
    }

    @Test
    fun `non-zero first telemetry sample preserves flow after TUN ready`() {
        val collector = collector()
        val generation = collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-a")
        collector.recordTunReady(generation)

        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                tunnelStats = TunnelStats(txPackets = 10_000L, rxPackets = 20_000L),
            ),
        )

        assertEquals("under_1s", collector.snapshot().timeToFirstFlow)
        assertEquals("bidirectional_observed", collector.snapshot().postStartDataPlaneOutcome)
    }

    @Test
    fun `replacement TUN resets counters without mixing one way outcomes`() {
        val collector = collector()
        val generation = collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-a")
        collector.recordTunReady(generation)
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(txBytes = 100L)),
        )
        assertEquals("outbound_only", collector.snapshot().postStartDataPlaneOutcome)

        collector.recordTunReady(generation)
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(rxBytes = 1L)),
        )

        assertEquals("outbound_only", collector.snapshot().postStartDataPlaneOutcome)
    }

    @Test
    fun `replacement TUN cannot downgrade bidirectional evidence or first milestones`() {
        var elapsed = 1_000L
        val collector = collector(elapsed = { elapsed })
        val generation = collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-a")
        elapsed = 1_500L
        collector.recordTunReady(generation)
        elapsed = 2_500L
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                tunnelStats = TunnelStats(txBytes = 100L, rxBytes = 200L),
            ),
        )

        elapsed = 8_000L
        collector.recordTunReady(generation)
        elapsed = 12_000L
        collector.recordTunnelTelemetry(
            generation,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(txBytes = 1L)),
        )

        val receipt = collector.snapshot()
        assertEquals("under_1s", receipt.timeToTun)
        assertEquals("1_to_5s", receipt.timeToFirstFlow)
        assertEquals("bidirectional_observed", receipt.postStartDataPlaneOutcome)
    }

    @Test
    fun `duplicate starts for one service instance retain the active generation and milestones`() {
        var generationCalls = 0
        val collector =
            collector(
                generation = {
                    generationCalls += 1
                    "generation-$generationCalls"
                },
            )
        val first = collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-a")
        collector.recordTunReady(first)

        val duplicate = collector.beginStart(processDeathRecoveryStartAction, "instance-a")

        assertEquals(first, duplicate)
        assertEquals(1, generationCalls)
        assertEquals("under_1s", collector.snapshot().timeToTun)
        assertEquals("explicit_user", collector.snapshot().startOrigin)
    }

    @Test
    fun `stale generation cannot update a superseding service instance`() {
        val generations = ArrayDeque(listOf("generation-a", "generation-b"))
        val collector = collector(generation = { generations.removeFirst() })
        val stale = collector.beginStart(com.poyka.ripdpi.data.startAction, "instance-a")
        collector.beginStart(processDeathRecoveryStartAction, "instance-b")

        collector.recordTunReady(stale)
        collector.recordTunnelTelemetry(
            stale,
            NativeRuntimeSnapshot.idle(source = "tunnel").copy(tunnelStats = TunnelStats(txBytes = 1L)),
        )

        assertEquals(PresentReceiptValue, collector.snapshot().generation)
        assertEquals("not_observed", collector.snapshot().timeToTun)
        assertEquals("pending", collector.snapshot().postStartDataPlaneOutcome)
    }

    @Test
    fun `every recovery entry point has a distinct stable origin`() {
        val cases =
            listOf(
                null to "process_death",
                processDeathRecoveryStartAction to "process_death",
                VpnService.SERVICE_INTERFACE to "always_on",
                bootRecoveryStartAction to "boot",
                packageReplacedRecoveryStartAction to "package_replaced",
                com.poyka.ripdpi.data.startAction to "explicit_user",
                diagnosticsStartAction to "diagnostics_resume",
                transportFailoverRestartAction to "transport_failover",
                startupFallbackStartAction to "startup_fallback",
            )

        cases.forEach { (action, expected) ->
            assertEquals(expected, classifyRecoveryStartOrigin(action))
            assertTrue(isRecoveryReceiptStartAction(action))
        }
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

    @Test
    fun `milestones survive collector recreation without persisting raw identifiers`() {
        val persistence = InMemoryRecoveryReceiptPersistence()
        val rawGeneration = "00000000-0000-0000-0000-00000000000a"
        val rawServiceInstance = "service-instance-sensitive"
        val first = collector(generation = { rawGeneration }, persistence = persistence)
        val generation = first.beginStart(bootRecoveryStartAction, rawServiceInstance)
        first.recordForegroundService(
            generation = generation,
            userUnlocked = false,
            policy = AndroidHardKillSwitchStateReader.fromPlatformFlags(alwaysOn = true, lockdown = true),
        )
        first.recordTunReady(generation)

        val restored = collector(persistence = persistence)

        assertEquals(first.snapshot(), restored.snapshot())
        assertEquals(PresentReceiptValue, restored.snapshot().generation)
        assertFalse(persistence.serializedState().contains(rawGeneration))
        assertFalse(persistence.serializedState().contains(rawServiceInstance))
    }

    @Test
    fun `user unlock advances the current generation and survives recreation`() {
        val persistence = InMemoryRecoveryReceiptPersistence()
        val first = collector(persistence = persistence)
        val generation = first.beginStart(bootRecoveryStartAction, "instance-a")
        first.recordForegroundService(
            generation = generation,
            userUnlocked = false,
            policy = AndroidHardKillSwitchStateReader.fromPlatformFlags(alwaysOn = true, lockdown = true),
        )

        first.recordUserUnlocked(generation)

        assertEquals("enabled", collector(persistence = persistence).snapshot().userUnlocked)
    }

    @Test
    fun `collector surfaces unavailable device protected persistence explicitly`() {
        val persistence =
            InMemoryRecoveryReceiptPersistence(
                availability =
                    RemoteDeviceRecoveryReceiptPersistenceAvailability.DeviceProtectedStorageUnavailable,
            )

        val receipt = collector(persistence = persistence).snapshot()

        assertEquals("device_protected_storage_unavailable", receipt.persistenceAvailability)
    }

    private fun collector(
        elapsed: () -> Long = { 1_000L },
        generation: () -> String = { "generation" },
        persistence: RemoteDeviceRecoveryReceiptPersistence = InMemoryRecoveryReceiptPersistence(),
    ): RemoteDeviceRecoveryReceiptCollector =
        RemoteDeviceRecoveryReceiptCollector(
            elapsedRealtime = elapsed,
            generationFactory = generation,
            persistence = persistence,
        )
}

private class InMemoryRecoveryReceiptPersistence(
    override val availability: RemoteDeviceRecoveryReceiptPersistenceAvailability =
        RemoteDeviceRecoveryReceiptPersistenceAvailability.Available,
) : RemoteDeviceRecoveryReceiptPersistence {
    private var state: PersistedRemoteDeviceRecoveryReceipt? = null

    override fun load(): PersistedRemoteDeviceRecoveryReceipt? = state

    override fun compareAndSet(
        expectedGenerationHash: String?,
        state: PersistedRemoteDeviceRecoveryReceipt,
    ): Boolean {
        if (this.state?.generationHash != expectedGenerationHash) return false
        this.state = state.copy(receipt = state.receipt.copy())
        return true
    }

    fun serializedState(): String = state.toString()
}
