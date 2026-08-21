package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RawPathSettlementRoomTest : DiagnosticsRoomStoreTestBase() {
    @Test
    fun `raw path settlement publishes receipt and terminal only for current durable marker`() =
        runTest {
            val scanStore = RoomDiagnosticsScanRecordStore(db, dao)
            val artifactStore = RoomDiagnosticsArtifactStore(db, dao)
            val running =
                scanSession(id = "scan-settlement", startedAt = 20L, finishedAt = null)
                    .copy(status = "running", summary = "Pending")
            val terminal = running.copy(status = "completed", summary = "Complete", finishedAt = 30L)
            val receipt = context(id = "ctx-settlement", sessionId = running.id, capturedAt = 30L)
            val marker =
                DiagnosticsDurableStateEntity(
                    key = "$RawPathSettlementDurableStatePrefix${running.id}",
                    value = "pending-settlement",
                    updatedAt = 30L,
                )
            scanStore.upsertScanSession(running)
            artifactStore.stageRawPathSettlement(marker)

            assertFalse(
                artifactStore.commitRawPathSettlement(
                    marker = marker.copy(value = "stale-settlement"),
                    context = receipt,
                    terminalSession = terminal,
                ),
            )
            assertEquals("running", scanStore.getScanSession(running.id)?.status)
            assertTrue(artifactStore.getContextsForSession(running.id).isEmpty())
            assertNotNull(artifactStore.getDurableState(marker.key))

            assertTrue(artifactStore.commitRawPathSettlement(marker, receipt, terminal))
            assertEquals("completed", scanStore.getScanSession(running.id)?.status)
            assertEquals(listOf(receipt), artifactStore.getContextsForSession(running.id))
            assertNull(artifactStore.getDurableState(marker.key))

            val malformedRunning = running.copy(id = "scan-malformed", summary = "Pending")
            val malformedMarker =
                marker.copy(
                    key = "$RawPathSettlementDurableStatePrefix${malformedRunning.id}",
                    value = "not-json",
                )
            val quarantineMarker =
                DiagnosticsDurableStateEntity(
                    key = "$RawPathSettlementQuarantineStatePrefix${malformedRunning.id}",
                    value = "malformed_receipt_v1",
                    updatedAt = 31L,
                )
            scanStore.upsertScanSession(malformedRunning)
            artifactStore.stageRawPathSettlement(malformedMarker)

            assertTrue(
                artifactStore.quarantineMalformedRawPathSettlement(
                    marker = malformedMarker,
                    quarantineMarker = quarantineMarker,
                    sessionId = malformedRunning.id,
                    terminalSummary = "Unreadable settlement",
                    finishedAt = 31L,
                ),
            )
            assertEquals("failed", scanStore.getScanSession(malformedRunning.id)?.status)
            assertNull(artifactStore.getDurableState(malformedMarker.key))
            assertEquals(quarantineMarker, artifactStore.getDurableState(quarantineMarker.key))
        }
}
