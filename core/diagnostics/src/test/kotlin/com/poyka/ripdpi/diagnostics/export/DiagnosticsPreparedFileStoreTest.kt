package com.poyka.ripdpi.diagnostics.export

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticsPreparedFileStoreTest {
    @Test fun `unpublished partial reservation cannot poison published lease reconciliation`() {
        val filesDir = Files.createTempDirectory("prepared-reservation").toFile()
        val root = filesDir.resolve("diagnostics-prepared").apply { mkdirs() }
        val lease = DiagnosticsExportLeaseId.create().encoded
        val temporaryId = DiagnosticsExportLeaseId.create().encoded
        val interrupted = root.resolve(".reserve-$lease-$temporaryId").apply { mkdirs() }
        interrupted.resolve("lease-partial.tmp").writeText("{")
        val store = DiagnosticsPreparedFileStore(filesDir, Json)
        assertEquals(emptyList<DiagnosticsExportLeaseRecord>(), store.records())
        val id = DiagnosticsExportLeaseId.create()
        store.reserve(id, DiagnosticsExportPurpose.ShareArchive, PreparedExportTime(1_700_000_000_000L, 1000L, 1))
        assertEquals(listOf(id.encoded), store.records().map { it.leaseId })
        assertTrue(root.resolve(id.encoded).resolve("lease.json").isFile)
    }

    @Test fun `corrupt published journal fails closed and keeps unknown contents intact`() {
        val filesDir = Files.createTempDirectory("prepared-corrupt").toFile()
        val id = DiagnosticsExportLeaseId.create()
        val directory = filesDir.resolve("diagnostics-prepared").resolve(id.encoded).apply { mkdirs() }
        directory.resolve("lease.json").writeText("{")
        val ownedData = directory.resolve("ripdpi-diagnostics.zip").apply { writeText("unverified") }
        assertThrows(SerializationException::class.java) { DiagnosticsPreparedFileStore(filesDir, Json).records() }
        assertEquals("unverified", ownedData.readText())
    }

    @Test fun `restored traversal and corrupted filename cannot reach a foreign file`() {
        val filesDir = Files.createTempDirectory("prepared-path").toFile()
        val foreign = filesDir.resolve("foreign.txt").apply { writeText("keep") }
        val store = DiagnosticsPreparedFileStore(filesDir, Json)
        val id = DiagnosticsExportLeaseId.create()
        val record =
            store.reserve(
                id,
                DiagnosticsExportPurpose.ShareArchive,
                PreparedExportTime(1_700_000_000_000L, 1000L, 1),
            )
        filesDir
            .resolve("diagnostics-prepared")
            .resolve(id.encoded)
            .resolve("lease.json")
            .writeText(Json.encodeToString(record.copy(fileName = "../../foreign.txt")))
        assertEquals(null, DiagnosticsExportLeaseId.parse("../../foreign.txt"))
        assertThrows(IllegalStateException::class.java) { store.read(id) }
        assertEquals("keep", foreign.readText())
    }

    @Test fun `failed directory deletion cannot poison later lease reconciliation`() {
        val filesDir = Files.createTempDirectory("prepared-discard-recovery").toFile()
        var failDirectoryOnce = true
        val store =
            DiagnosticsPreparedFileStore(filesDir, Json) { file ->
                if (file.isDirectory && failDirectoryOnce) {
                    failDirectoryOnce = false
                    false
                } else {
                    file.delete()
                }
            }
        val id = DiagnosticsExportLeaseId.create()
        val record =
            store.reserve(
                id,
                DiagnosticsExportPurpose.ShareArchive,
                PreparedExportTime(1_700_000_000_000L, 1000L, 1),
            )
        assertThrows(IllegalStateException::class.java) { store.deleteReservation(record) }
        assertEquals(listOf(DiagnosticsExportLeasePhase.DiscardRequested), store.records().map { it.phase })
        store.deleteReservation(record)
        assertEquals(emptyList<DiagnosticsExportLeaseRecord>(), store.records())
        val next = DiagnosticsExportLeaseId.create()
        store.reserve(next, DiagnosticsExportPurpose.ShareArchive, PreparedExportTime(1_700_000_000_000L, 1000L, 1))
        assertEquals(listOf(next.encoded), store.records().map { it.leaseId })
    }
}
