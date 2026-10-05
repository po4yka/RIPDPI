package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedArtifactReader
import com.poyka.ripdpi.diagnostics.export.PreparedExportClock
import com.poyka.ripdpi.diagnostics.export.PreparedExportTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class PreparedDiagnosticsExportTimeTest : DiagnosticsArchiveExporterTestBase() {
    @Test fun `new exporter restores exact handed off bytes and backward wall time fails closed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            var now = 1_700_000_000_000L
            val session = diagnosticsSession("restore-session", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val first = exporter(stores, context, PreparedExportClock { PreparedExportTime(now, 1000L, 1) })
            val id = DiagnosticsExportLeaseId.create()
            val prepared = first.prepare(id, preparation(session.id))
            val file = File(first.consume(id).absolutePath)
            stores.upsertScanSession(session.copy(summary = "changed source", reportJson = null))
            val restored = exporter(stores, context, PreparedExportClock { PreparedExportTime(now, 1000L, 1) })
            assertEquals(prepared.summary, restored.inspect(id).summary)
            assertTrue(restored.validateHandoff(id).absolutePath == file.absolutePath)
            now--
            var rejected = false
            try {
                restored.inspect(id)
            } catch (_: DiagnosticsArchiveException) {
                rejected = true
            }
            assertTrue("Backward clock retained an eligible lease", rejected)
            restored.cleanupCache()
            assertFalse(file.exists())
        }

    @Test fun `three day boundary expires the lease and removes only its record and file`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            var now = 1_700_000_000_000L
            val session = diagnosticsSession("expiry-session", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter = exporter(stores, context, PreparedExportClock { PreparedExportTime(now, 1000L, 1) })
            val id = DiagnosticsExportLeaseId.create()
            exporter.prepare(id, preparation(session.id))
            val file = File(exporter.consume(id).absolutePath)
            now += DiagnosticsArchiveFormat.maxArchiveAgeMs
            var rejected = false
            try {
                exporter.validateHandoff(id)
            } catch (_: DiagnosticsArchiveException) {
                rejected = true
            }
            assertTrue("Expired lease was accepted", rejected)
            exporter.cleanupCache()
            assertFalse(file.exists())
            assertFalse(stores.getExportRecords().any { it.id == id.encoded })
        }

    @Test fun `rollback after a later successful read cannot extend lease after restart`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            var now = 1_700_000_000_000L
            val session = diagnosticsSession("clock-checkpoint", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val first = exporter(stores, context, PreparedExportClock { PreparedExportTime(now, 1000L, 1) })
            val id = DiagnosticsExportLeaseId.create()
            val preview = first.prepare(id, preparation(session.id))
            first.consume(id)
            now += 2L * 24 * 60 * 60 * 1000
            val reader =
                DiagnosticsPreparedArtifactReader(
                    context.filesDir,
                    PreparedExportClock { PreparedExportTime(now, 1000L, 1) },
                )
            reader.checkedFile(id, preview.fileName)
            now -= 24L * 60 * 60 * 1000
            val restored = exporter(stores, context, PreparedExportClock { PreparedExportTime(now, 1000L, 1) })
            var rejected = false
            try {
                restored.validateHandoff(id)
            } catch (_: DiagnosticsArchiveException) {
                rejected = true
            }
            assertTrue("A later-observed clock rollback extended the lease", rejected)
        }

    @Test fun `unobserved wall rollback cannot extend monotonic lifetime`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            val created = 1_700_000_000_000L
            var time = PreparedExportTime(created, 1000L, 7)
            val clock = PreparedExportClock { time }
            val session = diagnosticsSession("monotonic-expiry", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val first = exporter(stores, context, clock)
            val id = DiagnosticsExportLeaseId.create()
            val preview = first.prepare(id, preparation(session.id))
            first.consume(id)
            // No intervening read: wall time advances only one day, elapsed time advances three.
            time =
                PreparedExportTime(
                    created + DiagnosticsArchiveFormat.maxArchiveAgeMs / 3,
                    1000L + DiagnosticsArchiveFormat.maxArchiveAgeMs,
                    7,
                )
            val reader = DiagnosticsPreparedArtifactReader(context.filesDir, clock)
            var rejected = false
            try {
                reader.checkedFile(id, preview.fileName)
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue("Wall rollback extended actual monotonic lifetime", rejected)
            val restored = exporter(stores, context, clock)
            restored.cleanupCache()
            assertFalse(stores.getExportRecords().any { it.id == id.encoded })
        }

    @Test fun `boot change or unavailable proof rejects old capability and new boot can prepare`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            var time = PreparedExportTime(1_700_000_000_000L, 1000L, 7)
            val clock = PreparedExportClock { time }
            val session = diagnosticsSession("boot-fence", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val first = exporter(stores, context, clock)
            val id = DiagnosticsExportLeaseId.create()
            val preview = first.prepare(id, preparation(session.id))
            first.consume(id)
            val reader = DiagnosticsPreparedArtifactReader(context.filesDir, clock)
            listOf(null, 8).forEach { boot ->
                time = time.copy(bootCount = boot)
                var rejected = false
                try {
                    reader.checkedFile(id, preview.fileName)
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                assertTrue("Old capability survived missing or changed boot proof", rejected)
            }
            val restored = exporter(stores, context, clock)
            restored.cleanupCache()
            val replacement = DiagnosticsExportLeaseId.create()
            restored.prepare(replacement, preparation(session.id))
            val handoff = restored.consume(replacement)
            assertTrue(reader.checkedFile(replacement, handoff.preview.fileName).isFile)
        }

    private fun exporter(
        stores: FakeDiagnosticsHistoryStores,
        context: TestContext,
        clock: PreparedExportClock,
    ) = createArchiveExporterForTest(
        stores,
        context,
        false,
        compositeRunService,
        json,
        preparedManager = preparedExportManagerForTest(context, stores, json, clock),
    )

    private fun preparation(sessionId: String) =
        DiagnosticsExportPreparation.Archive(
            DiagnosticsArchiveRequest(sessionId, reason = DiagnosticsArchiveReason.SHARE_ARCHIVE, requestedAt = 10),
            DiagnosticsExportPurpose.ShareArchive,
        )
}
