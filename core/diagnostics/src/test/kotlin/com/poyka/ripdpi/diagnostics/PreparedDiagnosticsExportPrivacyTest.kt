package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipFile

internal class PreparedDiagnosticsExportPrivacyTest : DiagnosticsArchiveExporterTestBase() {
    @Test fun `actual zip summary redacts session profile and strategy before projection`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session =
                diagnosticsSession("session-preview", "default", ScanPathMode.IN_PATH.name, "completed")
                    .copy(
                        approachProfileName = "https://private-profile.example/path",
                        strategyLabel = "https://private-strategy.example/path",
                    )
            seedSingleSessionStore(stores, session)
            val archive =
                createArchiveExporter(stores).createArchive(
                    DiagnosticsArchiveRequest(
                        requestedSessionId = session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 10L,
                    ),
                )
            ZipFile(archive.absolutePath).use { zip ->
                val summary = zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().readText()
                assertFalse("Profile endpoint leaked into final summary", summary.contains("private-profile.example"))
                assertFalse("Strategy endpoint leaked into final summary", summary.contains("private-strategy.example"))
            }
        }

    @Test fun `user supplied profile and strategy names are not exported in any zip entry`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val names = listOf("Alice private banking profile", "Alice private workstation strategy")
            val ids = List(4) { UUID.randomUUID().toString() }
            val session =
                diagnosticsSession(ids[0], ids[1], ScanPathMode.IN_PATH.name, "completed")
                    .copy(
                        approachProfileName = names[0],
                        strategyLabel = names[1],
                        approachProfileId = ids[2],
                        strategyId = ids[3],
                    )
            seedSingleSessionStore(stores, session)
            val exporter = createArchiveExporter(stores)
            val id =
                com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
                    .create()
            exporter.prepare(
                id,
                DiagnosticsExportPreparation.Archive(
                    DiagnosticsArchiveRequest(
                        session.id,
                        reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                        requestedAt = 10L,
                    ),
                    DiagnosticsExportPurpose.ShareArchive,
                ),
            )
            val handoff = exporter.consume(id)
            ZipFile(handoff.absolutePath).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val text = zip.getInputStream(entry).bufferedReader().readText()
                    (names + ids).forEach { name ->
                        assertFalse("Private label leaked into ${entry.name}", text.contains(name))
                    }
                }
            }
        }
}
