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
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
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
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, store, prefs)

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
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, store, prefs)

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
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, store, prefs)

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
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(firstStore, firstStore, prefs)
            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record("run-a", backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted))

            val secondStore = RecordingArtifactWriteStore()
            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(secondStore, secondStore, prefs)
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
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(firstStore, firstStore, prefs)
            val event = backgroundEvent(DeviceRuntimeBackgroundSurvivalPhase.ScreenOffStarted)

            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record("run-a", event)
            firstWriter.record("run-a", event)

            val ids = firstStore.nativeEvents.map(NativeSessionEventEntity::id)
            assertEquals(1, ids.size)
            assertNotEquals(ids[0], "run-a")
        }

    @Test
    fun `durable writer clears stale pending pref when terminal already exists`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val firstWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, store, prefs)

            firstWriter.beginRun("run-a", observedAtMillis = 10L)
            firstWriter.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            prefs.edit().putString(PendingGenerationKey, "run-a").commit()

            val recreatedWriter = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, store, prefs)
            recreatedWriter.beginRun("run-b", observedAtMillis = 30L)

            assertEquals(1, store.nativeEvents.size)
            assertTrue(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("phase=after_wake"),
            )
            assertFalse(store.nativeEvents.any { it.message.contains("phase=run_interrupted") })
        }

    @Test
    fun `durable writer uses one run-terminal id for terminal outcomes`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val writer = DefaultRemoteDeviceAcceptanceEvidenceWriter(store, store, prefs)

            writer.beginRun("run-a", observedAtMillis = 10L)
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Inconclusive,
                    reason = DeviceRuntimeBackgroundSurvivalReason.Cancelled,
                ),
            )
            writer.record(
                "run-a",
                backgroundEvent(
                    phase = DeviceRuntimeBackgroundSurvivalPhase.AfterWake,
                    outcome = DeviceRuntimeBackgroundSurvivalOutcome.Failed,
                    reason = DeviceRuntimeBackgroundSurvivalReason.ServiceStopped,
                ),
            )

            assertEquals(1, store.nativeEvents.size)
            assertTrue(
                store
                    .nativeEvents
                    .single()
                    .message
                    .contains("reason=service_stopped"),
            )
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
private const val PendingGenerationKey = "pending_generation"

private class RecordingArtifactWriteStore :
    DiagnosticsArtifactQueryStore,
    DiagnosticsArtifactWriteStore {
    val nativeEvents = mutableListOf<NativeSessionEventEntity>()

    override suspend fun getSnapshotsForSession(
        sessionId: String,
        limit: Int,
    ): List<NetworkSnapshotEntity> = emptyList()

    override suspend fun getContextsForSession(
        sessionId: String,
        limit: Int,
    ): List<DiagnosticContextEntity> = emptyList()

    override suspend fun getLatestTelemetrySampleForFingerprint(
        activeMode: String,
        fingerprintHash: String,
        createdAfter: Long,
    ): TelemetrySampleEntity? = null

    override suspend fun getNativeEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<NativeSessionEventEntity> = nativeEvents.filter { event -> event.sessionId == sessionId }.take(limit)

    override suspend fun getNativeEventById(id: String): NativeSessionEventEntity? =
        nativeEvents.firstOrNull { event -> event.id == id }

    override suspend fun getGlobalNativeEvents(limit: Int): List<NativeSessionEventEntity> =
        nativeEvents.filter { event -> event.sessionId == null }.take(limit)

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        nativeEvents.removeAll { existing -> existing.id == event.id }
        nativeEvents += event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit
}
