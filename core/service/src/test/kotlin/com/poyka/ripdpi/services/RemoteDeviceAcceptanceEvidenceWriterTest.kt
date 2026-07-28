package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalOutcome
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalPhase
import com.poyka.ripdpi.data.DeviceRuntimeBackgroundSurvivalReason
import com.poyka.ripdpi.data.DeviceRuntimeDataPlaneCounters
import com.poyka.ripdpi.data.DeviceRuntimeEvidence
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceAcceptanceEvidenceWriterTest {
    @Test
    fun `durable writer persists pending start before returning`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))

            val event = store.nativeEvents.single()
            assertEquals("run-a", event.runtimeId)
            assertEquals("remote_device_acceptance", event.source)
            assertTrue(event.message.contains("run_generation=run-a"))
            assertTrue(event.message.contains("phase=screen_off_started"))
            assertTrue(event.message.contains("outcome=pending"))
            assertFalse(event.message.contains("ssid", ignoreCase = true))
            assertFalse(event.message.contains("bssid", ignoreCase = true))
            assertFalse(event.message.contains("serial", ignoreCase = true))
        }

    @Test
    fun `durable writer clears pending run after failed or cancelled terminal event`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            writer.beginRun("run-b", observedAtMillis = 30L)
            writer.record("run-b", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.record(
                "run-b",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )
            writer.beginRun("run-c", observedAtMillis = 40L)

            assertTrue(store.nativeEvents[1].message.contains("reason=cancelled"))
            assertTrue(store.nativeEvents[3].message.contains("reason=service_stopped"))
            assertFalse(store.nativeEvents.any { it.message.contains("interrupted_before_next_run") })
        }

    @Test
    fun `durable writer marks pending run interrupted when a new run starts`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.beginRun("run-b", observedAtMillis = 30L)

            val interrupted = store.nativeEvents.last()
            assertEquals("run-a", interrupted.runtimeId)
            assertEquals(30L, interrupted.createdAt)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
        }

    private fun backgroundEvent(
        phase: DeviceRuntimeBackgroundSurvivalPhase,
        outcome: DeviceRuntimeBackgroundSurvivalOutcome = DeviceRuntimeBackgroundSurvivalOutcome.Pending,
        reason: DeviceRuntimeBackgroundSurvivalReason? = null,
    ): DeviceRuntimeEvidence.BackgroundSurvival =
        DeviceRuntimeEvidence.BackgroundSurvival(
            mode = Mode.VPN,
            phase = phase,
            outcome = outcome,
            reason = reason,
            countersBefore = DeviceRuntimeDataPlaneCounters(tunnelTxPackets = 1L),
            observedAtMillis = 20L,
        )
}

private class RecordingArtifactWriteStore : DiagnosticsArtifactWriteStore {
    val nativeEvents = mutableListOf<NativeSessionEventEntity>()

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        nativeEvents += event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit
}
