package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompletenessPayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveManifest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSessionSelectionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveAutomaticSessionTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `automatic archive skips a running scan with a staged full report`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val completed = diagnosticsSession("completed", "default", ScanPathMode.RAW_PATH.name, "Completed")
            val running =
                diagnosticsSession("running", "default", ScanPathMode.RAW_PATH.name, "Staged", status = "running")
                    .copy(startedAt = 25L)
            seedSingleSessionStore(stores, completed)
            stores.sessionsState.value = listOf(running, completed)

            val archive = createArchiveExporter(stores).createArchive(automaticRequest())

            assertEquals(completed.id, archive.sessionId)
            ZipFile(archive.absolutePath).use { zip ->
                val payload = json.decodeFromString<DiagnosticsArchivePayload>(zip.readArchiveEntry("report.json"))
                assertEquals(completed.id, payload.primaryReport?.sessionId)
            }
        }

    @Test
    fun `automatic archive keeps newest completion when the full report exceeds the storage limit`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val latest =
                diagnosticsSession(
                    "finished-latest",
                    "default",
                    ScanPathMode.RAW_PATH.name,
                    "Latest",
                    reportJson = null,
                ).copy(finishedAt = 35L)
            val older =
                diagnosticsSession("finished-earlier", "default", ScanPathMode.RAW_PATH.name, "Earlier")
                    .copy(startedAt = 25L, finishedAt = 30L)
            seedSingleSessionStore(stores, latest)
            stores.sessionsState.value = listOf(older, latest)

            val archive = createArchiveExporter(stores).createArchive(automaticRequest())

            assertEquals(latest.id, archive.sessionId)
            ZipFile(archive.absolutePath).use { zip ->
                val payload = json.decodeFromString<DiagnosticsArchivePayload>(zip.readArchiveEntry("report.json"))
                val completeness =
                    json.decodeFromString<DiagnosticsArchiveCompletenessPayload>(
                        zip.readArchiveEntry("completeness.json"),
                    )
                assertEquals(latest.id, payload.session?.id)
                assertNull(payload.primaryReport)
                val manifest = json.decodeFromString<DiagnosticsArchiveManifest>(zip.readArchiveEntry("manifest.json"))
                assertEquals(
                    DiagnosticsArchiveSessionSelectionStatus.LATEST_COMPLETED_SESSION,
                    manifest.sessionSelectionStatus,
                )
                assertEquals("dns_blocked", payload.results.single().outcome)
                assertTrue(completeness.reasons.any { it.section == "report" && it.code == "full_report_unavailable" })
            }
        }

    private fun ZipFile.readArchiveEntry(name: String): String =
        getInputStream(getEntry(name)).bufferedReader().use { it.readText() }

    @Test
    fun `automatic archive exports live state when no scan has completed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val running =
                diagnosticsSession("running", "default", ScanPathMode.RAW_PATH.name, "Staged", status = "running")
            stores.sessionsState.value = listOf(running)

            val archive = createArchiveExporter(stores).createArchive(automaticRequest())

            assertNull(archive.sessionId)
            ZipFile(archive.absolutePath).use { zip ->
                val manifest = json.decodeFromString<DiagnosticsArchiveManifest>(zip.readArchiveEntry("manifest.json"))
                assertEquals(
                    DiagnosticsArchiveSessionSelectionStatus.LATEST_LIVE_STATE,
                    manifest.sessionSelectionStatus,
                )
            }
        }

    private fun automaticRequest() =
        DiagnosticsArchiveRequest(
            requestedSessionId = null,
            reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
            requestedAt = 40L,
        )
}
