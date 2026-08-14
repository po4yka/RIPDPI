package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayHealthDecisionRecorderTest {
    @Test
    fun `confirmed failure persists complete privacy safe decision receipt`() =
        runTest {
            val store = RecordingArtifactWriteStore()
            val recorder = RoomRelayHealthDecisionRecorder(store)

            recorder.record(
                RelayHealthDecisionReceipt(
                    profileToken = "fixture-opaque-profile-token",
                    relayTransport = "vless_reality",
                    targetCategory = RelayTargetCategory.ApplicationHttp,
                    scope = RelayHealthScope(persistentNetworkHash = "private-network-hash", sessionGeneration = 4),
                    runtimeId = "runtime-7",
                    decision =
                        RelayHealthDecision.ConfirmedFailed(
                            attemptId = "attempt-7",
                            decidedAtMs = 123L,
                            positiveEvidenceWatermark = 41L,
                            failureStage = RelayFailureStage.RealityTls,
                            permanent = false,
                            observationCount = 2,
                        ),
                    cleanupReceipt = RelayCleanupReceipt.Completed,
                ),
            )

            val event = requireNotNull(store.event)
            assertEquals(
                listOf(
                    "app",
                    "relay_health_decision",
                    "attempt-7",
                    "fixture-opaque-profile-token",
                    "vless_reality",
                    "reality_tls",
                    "application_http",
                    "41",
                    "confirmed_failed",
                    "persistent_network",
                    "completed",
                    "runtime-7",
                ),
                listOf(
                    event.source,
                    event.subsystem,
                    event.healthAttemptId,
                    event.relayProfileToken,
                    event.relayTransport,
                    event.failureStage,
                    event.relayTargetCategory,
                    event.positiveEvidenceWatermark?.toString(),
                    event.relayHealthDecision,
                    event.cooldownScope,
                    event.cleanupReceipt,
                    event.runtimeId,
                ),
            )
            assertEquals("relay_health_decision", event.message)
            assertEquals(null, event.connectionSessionId)
            assertEquals(null, event.fingerprintHash)
        }
}

private class RecordingArtifactWriteStore : DiagnosticsArtifactWriteStore {
    var event: NativeSessionEventEntity? = null

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        this.event = event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit
}
