package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeasePhase
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal class PreparedDiagnosticsExportLeaseTest : DiagnosticsArchiveExporterTestBase() {
    @Test fun `preview inventories actual final zip and durable confirmation is consumed once`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession("prepared-session", "default", ScanPathMode.IN_PATH.name, "captured report")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val id = DiagnosticsExportLeaseId.create()
            val preview = exporter.prepare(id, preparation(session.id, DiagnosticsExportPurpose.ShareArchive))
            assertEquals(DiagnosticsExportLeasePhase.Ready, preview.phase)
            val handoff = exporter.consume(id)
            val file = File(handoff.absolutePath)
            assertEquals(file.length(), preview.byteCount)
            ZipFile(file).use { zip ->
                assertEquals(
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList(),
                    preview.entryNames,
                )
                assertEquals(
                    zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().readText(),
                    preview.summary,
                )
                val integrity =
                    json
                        .parseToJsonElement(
                            zip.getInputStream(zip.getEntry("integrity.json")).bufferedReader().readText(),
                        ).jsonObject
                        .getValue("files")
                        .jsonArray
                assertEquals(
                    preview.entryNames.filter { it != "integrity.json" }.toSet(),
                    integrity
                        .map {
                            it.jsonObject
                                .getValue("name")
                                .jsonPrimitive.content
                        }.toSet(),
                )
                integrity.forEach { item ->
                    val record = item.jsonObject
                    val entry = zip.getEntry(record.getValue("name").jsonPrimitive.content)
                    val bytes = zip.getInputStream(entry).readBytes()
                    assertEquals(bytes.size.toLong(), record.getValue("byteCount").jsonPrimitive.long)
                    val hash =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                    assertEquals(hash, record.getValue("sha256").jsonPrimitive.content)
                    assertEquals(CRC32().apply { update(bytes) }.value, entry.crc)
                }
            }
            assertTrue(stores.getExportRecords().any { it.id == id.encoded && it.uri == file.absolutePath })
            assertEquals(DiagnosticsExportLeasePhase.HandedOff, exporter.inspect(id).phase)
            var rejected = false
            try {
                exporter.consume(id)
            } catch (_: DiagnosticsArchiveException) {
                rejected = true
            }
            assertTrue("A second confirmation was accepted", rejected)
            exporter.cleanupCache()
            assertTrue("Chooser handoff released the file", file.isFile)
        }

    @Test fun `SAF copies prepared bytes after source changes then owned discard removes file and record`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = diagnosticsSession("save-session", "default", ScanPathMode.IN_PATH.name, "captured report")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val id = DiagnosticsExportLeaseId.create()
            exporter.prepare(id, preparation(session.id, DiagnosticsExportPurpose.SaveArchive))
            val handoff = exporter.consume(id)
            val file = File(handoff.absolutePath)
            val captured = file.readBytes()
            stores.upsertScanSession(session.copy(summary = "changed after preview", reportJson = null))
            val saved = ByteArrayOutputStream()
            exporter.copyPrepared(id, saved)
            assertArrayEquals(captured, saved.toByteArray())
            exporter.discard(id)
            assertFalse(file.exists())
            assertFalse(stores.getExportRecords().any { it.id == id.encoded })
        }

    @Test(timeout = 30_000)
    fun `a stalled save destination does not hold the archive lock`() =
        runBlocking {
            val stores = FakeDiagnosticsHistoryStores()
            val session = diagnosticsSession("stall-session", "default", ScanPathMode.IN_PATH.name, "captured report")
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val saved = DiagnosticsExportLeaseId.create()
            val other = DiagnosticsExportLeaseId.create()
            exporter.prepare(saved, preparation(session.id, DiagnosticsExportPurpose.SaveArchive))
            exporter.prepare(other, preparation(session.id, DiagnosticsExportPurpose.ShareArchive))
            exporter.consume(saved)
            exporter.consume(other)
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stalledProvider =
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        writing.countDown()
                        release.await()
                    }
                }
            val copy = launch(Dispatchers.IO) { exporter.copyPrepared(saved, stalledProvider) }
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS))

                val validated = withTimeoutOrNull(3_000) { exporter.validateHandoff(other) }

                assertTrue("archive lock held across the blocked write", validated != null)
            } finally {
                release.countDown()
                copy.join()
            }
        }

    private fun preparation(
        sessionId: String,
        purpose: DiagnosticsExportPurpose,
    ) = DiagnosticsExportPreparation.Archive(
        DiagnosticsArchiveRequest(sessionId, reason = DiagnosticsArchiveReason.SHARE_ARCHIVE, requestedAt = 10L),
        purpose,
    )
}
