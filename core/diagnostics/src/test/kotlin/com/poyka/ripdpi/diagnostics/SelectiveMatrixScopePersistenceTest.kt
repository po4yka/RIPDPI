package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.finalization.revokePersistedNetworkScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectiveMatrixScopePersistenceTest {
    @Test
    fun `post-persistence scope revocation updates exported rows and preserves raw attempts`() =
        runTest {
            val json = diagnosticsTestJson()
            val stores = FakeDiagnosticsHistoryStores()
            val attempt =
                ProbeResultEntity(
                    "attempt",
                    "test",
                    "selective_availability",
                    "control",
                    "matrix_target_available",
                    "[]",
                    1,
                )
            val summary =
                ProbeResultEntity(
                    "summary",
                    "test",
                    "selective_availability_summary",
                    "matrix",
                    "matrix_selective",
                    "[]",
                    1,
                )
            val report =
                EngineScanReportWire(
                    sessionId = "test",
                    profileId = SelectiveMatrixProfileId,
                    pathMode = ScanPathMode.RAW_PATH,
                    startedAt = 0,
                    finishedAt = 1,
                    summary = "Selective",
                )
            stores.persistCompletedScan(
                ScanSessionEntity(
                    id = "test",
                    profileId = SelectiveMatrixProfileId,
                    pathMode = "RAW_PATH",
                    serviceMode = null,
                    status = "completed",
                    summary = "Selective",
                    reportJson = json.encodeToString(EngineScanReportWire.serializer(), report),
                    startedAt = 0,
                    finishedAt = 1,
                ),
                listOf(attempt, summary),
            )
            revokePersistedNetworkScope("test", stores, json)
            val rows = stores.getProbeResults("test")
            assertEquals(attempt, rows.first())
            assertEquals("matrix_inconclusive", rows.last().outcome)
            assertTrue(rows.last().detailJson.contains("network_scope_unverified"))
            assertEquals(listOf("attempt", "summary"), rows.map { it.id })
        }
}
