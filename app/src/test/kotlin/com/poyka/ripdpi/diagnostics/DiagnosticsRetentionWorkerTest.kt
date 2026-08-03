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
    fun `periodic retention cleans expired pcap captures`() {
        val now = 1_700_000_000_000L
        val cacheDir = Files.createTempDirectory("pcap-periodic-retention").toFile()
        val pcap =
            cacheDir.resolve("diagnostics/0000000000000001-1-00.pcap").apply {
                parentFile.mkdirs()
                writeText("expired")
                setLastModified(now - 24L * 60L * 60L * 1_000L - 1L)
            }
        val fileStore = DiagnosticsArchiveFileStore(cacheDir, DiagnosticsArchiveClock { now })

        cleanupDiagnosticsPcapFiles(fileStore)

        assertFalse(pcap.exists())
    }

    private class RecordingRetentionStore : DiagnosticsHistoryRetentionStore {
        val calls = mutableListOf<Int>()

        override suspend fun trimOldData(retentionDays: Int) {
            calls += retentionDays
        }
    }
}
