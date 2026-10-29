package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.DiagnosticsSessionQueries
import kotlinx.serialization.json.Json

/** Looks up the stored proxy config of one candidate of a saved scan. The config never leaves core. */
fun interface ScanCandidateConfigSource {
    suspend fun proxyConfigJson(
        sessionId: String,
        candidateId: String,
    ): String?
}

class StoredScanCandidateConfigSource(
    private val scanRecordStore: DiagnosticsScanRecordStore,
    private val json: Json,
) : ScanCandidateConfigSource {
    override suspend fun proxyConfigJson(
        sessionId: String,
        candidateId: String,
    ): String? {
        val report =
            scanRecordStore
                .getScanSession(sessionId)
                ?.let { DiagnosticsSessionQueries.decodeScanReport(json, it.reportJson) }
                ?.strategyProbeReport ?: return null
        return (report.tcpCandidates + report.quicCandidates)
            .firstOrNull { it.id == candidateId }
            ?.proxyConfigJson
    }
}
