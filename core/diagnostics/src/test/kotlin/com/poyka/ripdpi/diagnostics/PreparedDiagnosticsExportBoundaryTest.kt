package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeasePhase
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedArtifactReader
import com.poyka.ripdpi.diagnostics.export.DiagnosticsPreparedFileStore
import com.poyka.ripdpi.diagnostics.export.PreparedExportClock
import com.poyka.ripdpi.diagnostics.export.PreparedExportTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class PreparedDiagnosticsExportBoundaryTest : DiagnosticsArchiveExporterTestBase() {
    @Test fun `unexpired previews and chooser leases do not consume the five normal archive slots`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = diagnosticsSession("retained", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val leases = List(2) { DiagnosticsExportLeaseId.create() }
            leases.forEach { exporter.prepare(it, preparation(session.id)) }
            val handedOff = File(exporter.consume(leases.first()).absolutePath)
            repeat(8) { exporter.createArchive(preparation(session.id).request) }
            exporter.cleanupCache()
            assertEquals(5, stores.getExportRecords().count { it.id !in leases.map { lease -> lease.encoded } })
            assertEquals(7, stores.getExportRecords().size)
            assertTrue(handedOff.isFile)
            leases.forEach { assertTrue(exporter.inspect(it).byteCount > 0) }
        }

    @Test fun `cancellation after committed record cleans exact owned archive and rethrows cancellation`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = diagnosticsSession("cancelled", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val kept = DiagnosticsExportLeaseId.create()
            exporter.prepare(kept, preparation(session.id))
            val keepFile = File(exporter.consume(kept).absolutePath)
            val inserted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancelled = DiagnosticsExportLeaseId.create()
            stores.afterInsertExportRecord = {
                if (it.id == cancelled.encoded) {
                    inserted.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            val attempt = launch { exporter.prepare(cancelled, preparation(session.id)) }
            inserted.await()
            val cancelledPath = stores.getExportRecords().single { it.id == cancelled.encoded }.uri
            attempt.cancel()
            release.complete(Unit)
            attempt.join()
            assertTrue(attempt.isCancelled)
            assertFalse(File(cancelledPath).exists())
            assertFalse(stores.getExportRecords().any { it.id == cancelled.encoded })
            assertTrue(keepFile.isFile)
            assertTrue(exporter.validateHandoff(kept).preview.byteCount > 0)
        }

    @Test fun `cleanup database failure is visible preserves retryable ownership and never deletes another lease`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = diagnosticsSession("cleanup", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val own = DiagnosticsExportLeaseId.create()
            val other = DiagnosticsExportLeaseId.create()
            exporter.prepare(own, preparation(session.id))
            exporter.prepare(other, preparation(session.id))
            val otherPath = exporter.consume(other).absolutePath
            stores.beforeGetExportRecords = { throw IllegalStateException("database unavailable") }
            var failed = false
            try {
                exporter.discard(own)
            } catch (_: DiagnosticsArchiveException) {
                failed = true
            }
            assertTrue("Cleanup error was swallowed", failed)
            assertTrue(stores.exportsState.value.any { it.id == own.encoded })
            assertTrue(File(otherPath).isFile)
            stores.beforeGetExportRecords = {}
            exporter.discard(own)
            assertFalse(stores.getExportRecords().any { it.id == own.encoded })
            assertTrue(exporter.validateHandoff(other).preview.byteCount > 0)
        }

    @Test fun `logs preview is bounded text but SAF copies exact full redacted UTF8 capture once`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            val raw = "😀".repeat(5000) + " https://private.example/path password=private-token"
            val expected = DiagnosticsLogRedactor().redactLogcat(raw)
            var captures = 0
            val collector =
                object : LogcatSnapshotCollector() {
                    override suspend fun capture(sinceTimestampMs: Long?): LogcatSnapshot {
                        captures++
                        return LogcatSnapshot(raw, "test", raw.toByteArray().size)
                    }
                }
            val exporter =
                createArchiveExporterForTest(
                    stores,
                    context,
                    false,
                    compositeRunService,
                    json,
                    preparedManager = preparedExportManagerForTest(context, stores, json, logcat = collector),
                )
            val id = DiagnosticsExportLeaseId.create()
            val preview = exporter.prepare(id, DiagnosticsExportPreparation.Logs)
            assertEquals("text/plain", preview.mimeType)
            assertEquals("ripdpi.log", preview.fileName)
            assertEquals(listOf("ripdpi.log"), preview.entryNames)
            assertEquals(4096, preview.summary.codePointCount(0, preview.summary.length))
            assertEquals(expected.toByteArray().size.toLong(), preview.byteCount)
            exporter.consume(id)
            val output = ByteArrayOutputStream()
            exporter.copyPrepared(id, output)
            assertArrayEquals(expected.toByteArray(), output.toByteArray())
            assertEquals(1, captures)
            exporter.discard(id)
            assertTrue(stores.getExportRecords().isEmpty())
        }

    @Test fun `delayed reader survives restart and rejects unconfirmed tampered expired and backward leases`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            var now = 1_700_000_000_000L
            val session = diagnosticsSession("reader", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter =
                createArchiveExporterForTest(
                    stores,
                    context,
                    false,
                    compositeRunService,
                    json,
                    preparedManager =
                        preparedExportManagerForTest(
                            context,
                            stores,
                            json,
                            PreparedExportClock { PreparedExportTime(now, 1000L, 1) },
                        ),
                )
            val id = DiagnosticsExportLeaseId.create()
            val preview = exporter.prepare(id, preparation(session.id))
            val reader =
                DiagnosticsPreparedArtifactReader(
                    context.filesDir,
                    PreparedExportClock { PreparedExportTime(now, 1000L, 1) },
                )
            assertRejected { reader.checkedFile(id, preview.fileName) }
            val handoff = exporter.consume(id)
            val bytes = File(handoff.absolutePath).readBytes()
            val restored =
                DiagnosticsPreparedArtifactReader(
                    context.filesDir,
                    PreparedExportClock { PreparedExportTime(now, 1000L, 1) },
                )
            assertArrayEquals(bytes, restored.checkedFile(id, preview.fileName).readBytes())
            assertRejected { restored.checkedFile(id, "other.zip") }
            now--
            assertRejected { restored.checkedFile(id, preview.fileName) }
            now += DiagnosticsArchiveFormat.maxArchiveAgeMs + 1
            assertRejected { restored.checkedFile(id, preview.fileName) }
            now -= DiagnosticsArchiveFormat.maxArchiveAgeMs
            File(handoff.absolutePath).appendText("tampered")
            assertRejected { restored.checkedFile(id, preview.fileName) }
        }

    @Test fun `stale concurrent manager phase write preserves reader clock checkpoints`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val context = TestContext()
            val wall = AtomicLong(1_700_000_000_000L)
            val elapsed = AtomicLong(1000L)
            val clock = PreparedExportClock { PreparedExportTime(wall.get(), elapsed.get(), 1) }
            val session = diagnosticsSession("concurrent-clock", "default", ScanPathMode.IN_PATH.name, "captured")
            seedSingleSessionStore(stores, session)
            val exporter =
                createArchiveExporterForTest(
                    stores,
                    context,
                    false,
                    compositeRunService,
                    json,
                    preparedManager = preparedExportManagerForTest(context, stores, json, clock),
                )
            val id = DiagnosticsExportLeaseId.create()
            val preview = exporter.prepare(id, preparation(session.id))
            exporter.consume(id)
            val managerFiles = DiagnosticsPreparedFileStore(context.filesDir, json)
            val captured = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val write =
                    worker.submit {
                        val stale = managerFiles.read(id)
                        captured.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                        managerFiles.write(stale.copy(phase = DiagnosticsExportLeasePhase.DiscardRequested))
                    }
                assertTrue(captured.await(5, TimeUnit.SECONDS))
                wall.addAndGet(24L * 60 * 60 * 1000)
                elapsed.addAndGet(24L * 60 * 60 * 1000)
                DiagnosticsPreparedArtifactReader(context.filesDir, clock).checkedFile(id, preview.fileName)
                resume.countDown()
                write.get(5, TimeUnit.SECONDS)
                val latest = managerFiles.read(id)
                assertEquals(wall.get(), latest.lastObservedAt)
                assertEquals(elapsed.get(), latest.lastObservedElapsedMs)
                assertEquals(DiagnosticsExportLeasePhase.DiscardRequested, latest.phase)
            } finally {
                resume.countDown()
                worker.shutdownNow()
            }
        }

    private fun preparation(sessionId: String) =
        DiagnosticsExportPreparation.Archive(
            DiagnosticsArchiveRequest(sessionId, reason = DiagnosticsArchiveReason.SHARE_ARCHIVE, requestedAt = 10),
            DiagnosticsExportPurpose.ShareArchive,
        )

    private fun assertRejected(read: () -> Unit) {
        var rejected = false
        try {
            read()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("An invalid delayed read was accepted", rejected)
    }
}
