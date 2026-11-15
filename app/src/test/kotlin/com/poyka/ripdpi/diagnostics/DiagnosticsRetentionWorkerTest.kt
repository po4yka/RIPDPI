package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRetentionStore
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveClock
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFileStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class DiagnosticsRetentionWorkerTest {
    @Test
    fun `retention runs without monitor or VPN lifecycle`() =
        runTest {
            val store = RecordingRetentionStore()

            trimDiagnosticsHistory(retentionDays = 17, retentionStore = store)

            assertEquals(listOf(17), store.calls)
        }

    @Test
    fun `periodic retention invokes capture cleanup with its configured storage`() =
        runTest {
            val cacheDir = Files.createTempDirectory("pcap-periodic-retention").toFile()
            try {
                val capture = cacheDir.resolve("expired.pcap").apply { writeText("expired") }
                val fileStore =
                    DiagnosticsArchiveFileStore(
                        cacheDir,
                        DiagnosticsArchiveClock { 1_700_000_000_000L },
                        cleanupCaptureStorage = { check(capture.delete()) },
                    )

                cleanupDiagnosticsPcapFiles(fileStore)

                assertFalse(capture.exists())
            } finally {
                cacheDir.deleteRecursively()
            }
        }

    private class RecordingRetentionStore : DiagnosticsHistoryRetentionStore {
        val calls = mutableListOf<Int>()

        override suspend fun trimOldData(retentionDays: Int) {
            calls += retentionDays
        }
    }
}
