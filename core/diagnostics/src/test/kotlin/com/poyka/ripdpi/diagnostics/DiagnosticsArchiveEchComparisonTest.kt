package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveReason
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRequest
import com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.ZipFile

internal class DiagnosticsArchiveEchComparisonTest : DiagnosticsArchiveExporterTestBase() {
    @Test
    fun `history and archive retain the ECH cause after scan completion`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val reasons = listOf("ech_config_missing", "ech_dns_failed", "ech_config_invalid", "ech_rejected")
            val detailsSerializer = ListSerializer(ProbeDetail.serializer())
            for ((index, reason) in reasons.withIndex()) {
                val details =
                    listOf(
                        ProbeDetail("tls13Outcome", "verified"),
                        ProbeDetail("echGreaseOutcome", "body_timeout"),
                        ProbeDetail("echOutcome", reason),
                        ProbeDetail("echBytes", "0"),
                    )
                val report =
                    EngineScanReportWire(
                        sessionId = "ech-scan-$index",
                        profileId = EchTransferComparisonProfileId,
                        pathMode = ScanPathMode.RAW_PATH,
                        startedAt = 10L,
                        finishedAt = 20L,
                        summary = "ECH transfer comparison",
                        results =
                            listOf(
                                ProbeResult("throughput_window", "ECH", "ech_transfer_comparison_recorded", details)
                                    .toEngineProbeResultWire(),
                            ),
                    )
                DiagnosticsReportPersister.persistScanReport(
                    report,
                    stores,
                    stores,
                    FakeServiceStateStore(),
                    json,
                    deferTerminal = false,
                )

                val history = stores.getProbeResults(report.sessionId).single()
                assertEquals(details, json.decodeFromString(detailsSerializer, history.detailJson))
                val archive =
                    createArchiveExporter(stores).createArchive(
                        DiagnosticsArchiveRequest(
                            requestedSessionId = report.sessionId,
                            reason = DiagnosticsArchiveReason.SHARE_ARCHIVE,
                            requestedAt = 40L,
                        ),
                    )
                ZipFile(archive.absolutePath).use { zip ->
                    val payload =
                        zip.getInputStream(zip.getEntry("report.json")).bufferedReader().use {
                            json.decodeFromString<DiagnosticsArchivePayload>(it.readText())
                        }

                    assertEquals(details, json.decodeFromString(detailsSerializer, payload.results.single().detailJson))
                    assertEquals(
                        details,
                        payload.primaryReport
                            ?.results
                            ?.single()
                            ?.details,
                    )
                }
            }
        }
}
