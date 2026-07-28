package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDeviceAcceptanceEvidenceWriterTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences(TestPrefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `durable writer persists pending start before returning`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, prefs)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))

            val event = store.nativeEvents.single()
            assertEquals("run-a", event.runtimeId)
            assertEquals("remote_device_acceptance", event.source)
            assertTrue(event.message.contains("run_generation=run-a"))
            assertTrue(event.message.contains("phase=screen_off_started"))
            assertTrue(event.message.contains("outcome=pending"))
            assertEquals(event.id, store.nativeEvents.single().id)
            assertFalse(event.message.contains("ssid", ignoreCase = true))
            assertFalse(event.message.contains("bssid", ignoreCase = true))
            assertFalse(event.message.contains("serial", ignoreCase = true))
        }

    @Test
    fun `durable writer clears pending run after failed or cancelled terminal event`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, prefs)

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
    fun `durable writer keeps pending ledger after screen-off probe pass`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, prefs)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.ScreenOffProbe,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Passed,
                ),
            )
            writer.beginRun("run-b", observedAtMillis = 30L)

            val interrupted = store.nativeEvents.last()
            assertEquals("run-a", interrupted.runtimeId)
            assertEquals(30L, interrupted.createdAt)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
        }

    @Test
    fun `durable writer reconciles pending ledger after writer recreation`() =
        runTest {
            val firstStore = RecordingArtifactWriteStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(firstStore, prefs)
            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))

            val secondStore = RecordingArtifactWriteStore()
            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(secondStore, prefs)
            recreatedWriter.beginRun("run-b", observedAtMillis = 30L)

            val interrupted = secondStore.nativeEvents.single()
            assertEquals("run-a", interrupted.runtimeId)
            assertTrue(interrupted.message.contains("phase=run_interrupted"))
            assertTrue(interrupted.message.contains("reason=interrupted_before_next_run"))
        }

    @Test
    fun `durable writer uses deterministic semantic ids`() =
        runTest {
            val firstStore = RecordingArtifactWriteStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(firstStore, prefs)
            val event = backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted)

            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record("run-a", event)
            firstWriter.record("run-a", event)

            val ids = firstStore.nativeEvents.map(NativeSessionEventEntity::id)
            assertEquals(ids[0], ids[1])
            assertNotEquals(ids[0], "run-a")
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

private const val TestPrefsName = "remote_acceptance_evidence_writer_test"

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
