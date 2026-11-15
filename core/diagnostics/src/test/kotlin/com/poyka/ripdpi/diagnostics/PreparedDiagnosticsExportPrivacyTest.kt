package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportLeaseId
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation
import com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPurpose
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun `prepared system share exposes Telegram ports and WSS without endpoint identities`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val session = diagnosticsSession("telegram-share", "ru-dpi-full", ScanPathMode.RAW_PATH.name, "completed")
            val report =
                EngineScanReportWire(
                    sessionId = session.id,
                    profileId = "ru-dpi-full",
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 1L,
                    finishedAt = 2L,
                    summary = "completed",
                    results =
                        listOf(
                            EngineProbeResultWire(
                                probeType = "telegram_availability",
                                target = "private-dc.example",
                                outcome = "partial",
                                details =
                                    listOf(
                                        ProbeDetail("downloadStatus", "ok"),
                                        ProbeDetail("uploadStatus", "not_run"),
                                        ProbeDetail("dcReachable", "1"),
                                        ProbeDetail("dcTotal", "2"),
                                        ProbeDetail("dcPlanned", "3"),
                                        ProbeDetail(
                                            "dcResults",
                                            "private-dc:ok:42ms:address=192.0.2.10:443;family=ipv4;path=socks5|" +
                                                "private-dc:fail::address=[2001:db8::1]:80;family=ipv6;path=direct",
                                        ),
                                        ProbeDetail("wsTunnelStatus", "open_timed_out"),
                                        ProbeDetail("wsTunnelPath", "socks5"),
                                        ProbeDetail("wsTunnelEndpoint", "wss://private-gateway.example/apiws"),
                                        ProbeDetail("wsTunnelError", "private-error.example"),
                                    ),
                            ),
                        ),
                )
            seedSingleSessionStore(
                stores,
                session.copy(reportJson = json.encodeToString(EngineScanReportWire.serializer(), report)),
            )
            val exporter = createArchiveExporter(stores)
            val id = DiagnosticsExportLeaseId.create()
            val preview =
                exporter.prepare(
                    id,
                    DiagnosticsExportPreparation.Archive(
                        DiagnosticsArchiveRequest(
                            requestedSessionId = session.id,
                            reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                            requestedAt = 10L,
                        ),
                        DiagnosticsExportPurpose.ShareSummary,
                    ),
                )
            val handoff = exporter.consume(id)
            ZipFile(handoff.absolutePath).use { zip ->
                assertEquals(
                    zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().readText(),
                    preview.summary,
                )
            }
            val facts =
                listOf(
                    "telegramOutcome=partial",
                    "telegramDownload=ok",
                    "telegramUpload=not_run",
                    "telegramTcpEndpoints reachable=1 attempted=2 planned=3",
                    "telegramTcp[0] port=443 status=ok family=ipv4 path=socks5",
                    "telegramTcp[1] port=80 status=fail family=ipv6 path=direct",
                    "telegramWssOpening status=open_timed_out path=socks5",
                )
            facts.forEach { assertTrue("Missing $it", it in preview.summary.lineSequence()) }
            listOf("192.0.2.10", "2001:db8::1", "private-dc", "private-gateway", "private-error").forEach {
                assertFalse("Private Telegram evidence leaked: $it", preview.summary.contains(it))
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
