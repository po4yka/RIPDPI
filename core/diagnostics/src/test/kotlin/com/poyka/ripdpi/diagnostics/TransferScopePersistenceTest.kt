package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.finalization.revokePersistedNetworkScope
import com.poyka.ripdpi.diagnostics.finalization.withoutNetworkScopeAuthority
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferScopePersistenceTest {
    private val json = diagnosticsTestJson()
    private val evidence =
        json.encodeToString(
            TransferEvidence.serializer(),
            TransferEvidence(
                runs =
                    listOf(
                        TransferMeasurement(
                            runIndex = 1,
                            runCount = 1,
                            receivedBodyByteCount = 128,
                            expectedBodyByteCount = 256,
                            elapsedMs = 30,
                            firstBodyByteMs = 10,
                            lastBodyProgressMs = 20,
                            terminationReason = "early_eof",
                            samples = listOf(TransferProgressSample(10, 64), TransferProgressSample(20, 128)),
                        ),
                    ),
            ),
        )
    private val details = listOf(ProbeDetail("transferEvidence", evidence), ProbeDetail("other", "preserved"))

    @Test
    fun `wire scope warning preserves transfer cause and measurements and is idempotent`() {
        val legacy = ProbeResult("throughput_window", "legacy", "normal")
        val result = ProbeResult("throughput_window", "control", "partial", details)
        val scoped = report(listOf(result, legacy)).withoutNetworkScopeAuthority()
        assertEquals("partial", scoped.results.first().outcome)
        assertEquals(details + ProbeDetail("transferNetworkScope", "unverified"), scoped.results.first().details)
        assertEquals(legacy.toEngineProbeResultWire(), scoped.results.last())
        assertEquals(scoped, scoped.withoutNetworkScopeAuthority())
    }

    @Test
    fun `late revocation atomically updates transfer and matrix rows without duplicate ids`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val transfer = row("transfer", "throughput_window", "partial", details)
            val matrix = row("matrix", "selective_availability_summary", "matrix_selective", emptyList())
            val legacy = row("legacy", "throughput_window", "normal", emptyList())
            val wire = report(listOf(ProbeResult("throughput_window", "control", "partial", details)))
            stores.persistCompletedScan(session(wire), listOf(transfer, matrix, legacy))
            var atomicWrites = 0
            var sessionOnlyWrites = 0
            val recordingStore =
                object : DiagnosticsScanRecordStore by stores {
                    override suspend fun persistCompletedScan(
                        session: ScanSessionEntity,
                        results: List<ProbeResultEntity>,
                    ) {
                        atomicWrites += 1
                        stores.persistCompletedScan(session, results)
                    }

                    override suspend fun upsertScanSession(session: ScanSessionEntity) {
                        sessionOnlyWrites += 1
                        stores.upsertScanSession(session)
                    }
                }
            revokePersistedNetworkScope("test", recordingStore, json)
            assertEquals(1, atomicWrites)
            assertEquals(0, sessionOnlyWrites)
            val rows = stores.getProbeResults("test")
            assertEquals(listOf("transfer", "matrix", "legacy"), rows.map { it.id })
            assertEquals("partial", rows.first().outcome)
            assertEquals(
                details + ProbeDetail("transferNetworkScope", "unverified"),
                json.decodeFromString(ListSerializer(ProbeDetail.serializer()), rows.first().detailJson),
            )
            assertEquals("matrix_inconclusive", rows[1].outcome)
            assertEquals(legacy, rows.last())
            val storedReport = json.decodeEngineScanReportWire(checkNotNull(stores.getScanSession("test")?.reportJson))
            assertTrue(
                storedReport.results
                    .first()
                    .details
                    .contains(ProbeDetail("transferNetworkScope", "unverified")),
            )
            revokePersistedNetworkScope("test", recordingStore, json)
            assertEquals(rows, stores.getProbeResults("test"))
        }

    private fun row(
        id: String,
        type: String,
        outcome: String,
        values: List<ProbeDetail>,
    ) = ProbeResultEntity(
        id,
        "test",
        type,
        "control",
        outcome,
        json.encodeToString(ListSerializer(ProbeDetail.serializer()), values),
        1,
    )

    private fun report(results: List<ProbeResult>) =
        EngineScanReportWire(
            sessionId = "test",
            profileId = "ru-throttling",
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 0,
            finishedAt = 1,
            summary = "Measured",
            results = results.map { it.toEngineProbeResultWire() },
        )

    private fun session(report: EngineScanReportWire) =
        ScanSessionEntity(
            id = "test",
            profileId = "ru-throttling",
            pathMode = "RAW_PATH",
            serviceMode = null,
            status = "completed",
            summary = "Measured",
            reportJson = json.encodeToString(EngineScanReportWire.serializer(), report),
            startedAt = 0,
            finishedAt = 1,
        )
}
