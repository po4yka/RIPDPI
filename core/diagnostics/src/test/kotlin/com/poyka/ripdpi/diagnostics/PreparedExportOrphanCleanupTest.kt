package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedArtifactReader
import com.poyka.ripdpi.diagnostics.export.PreparedExportClock
import com.poyka.ripdpi.diagnostics.export.PreparedExportTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class PreparedExportOrphanCleanupTest : DiagnosticsArchiveExporterTestBase() {
    @Test fun `cleanup removes a handed-off lease whose export record was wiped`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            val session = diagnosticsSession("reset", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter =
                createArchiveExporterForTest(stores, context, false, compositeRunService, json)
            val wiped = DiagnosticsExportLeaseId.create()
            val kept = DiagnosticsExportLeaseId.create()
            val wipedPreview = exporter.prepare(wiped, preparation(session.id))
            exporter.prepare(kept, preparation(session.id))
            val wipedFile = File(exporter.consume(wiped).absolutePath)
            val keptFile = File(exporter.consume(kept).absolutePath)
            // "Reset all settings" deletes the export records but not filesDir/diagnostics-prepared.
            stores.deleteExportRecords(listOf(wiped.encoded))

            exporter.cleanupCache()

            assertFalse(wipedFile.exists())
            assertTrue(keptFile.isFile)
            val reader =
                DiagnosticsPreparedArtifactReader(
                    context.filesDir,
                    PreparedExportClock { PreparedExportTime(1_700_000_000_000L, 1000L, 1) },
                )
            assertTrue(runCatching { reader.checkedFile(wiped, wipedPreview.fileName) }.isFailure)
        }

    private fun preparation(sessionId: String) =
        DiagnosticsExportPreparation.Archive(
            DiagnosticsArchiveRequest(sessionId, reason = DiagnosticsArchiveReason.SHARE_ARCHIVE, requestedAt = 10),
            DiagnosticsExportPurpose.ShareArchive,
        )
}
