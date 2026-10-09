package com.poyka.ripdpi.diagnostics.finalization

import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.diagnostics.application.PreparedDiagnosticsScan
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.ScanCompletionKind
import com.poyka.ripdpi.diagnostics.decodeEngineScanReportWire
import com.poyka.ripdpi.diagnostics.hasAuthoritativeManualConflictCancellation
import com.poyka.ripdpi.diagnostics.toEngineScanReportWire
import com.poyka.ripdpi.diagnostics.toScanReport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal const val NetworkScopeUnverifiedDiagnosis = "network_scope_unverified"
private const val NetworkScopeUnverifiedSummary =
    "Network continuity could not be verified. Results are inconclusive; repeat the scan."

/** Preserve measurements, but do not let history or Home consumers apply mixed-network advice. */
internal fun EngineScanReportWire.withoutNetworkScopeAuthority(): EngineScanReportWire =
    copy(
        summary = NetworkScopeUnverifiedSummary,
        completionKind =
            if (completionKind ==
                ScanCompletionKind.NORMAL
            ) {
                ScanCompletionKind.PARTIAL_RESULTS
            } else {
                completionKind
            },
        results =
            results.map { result ->
                if (result.probeType == "selective_availability_summary") {
                    result.copy(
                        outcome = "matrix_inconclusive",
                        details =
                            result.details.filterNot { it.key == "reason" } +
                                ProbeDetail("reason", "network_scope_unverified"),
                    )
                } else {
                    result
                }
            },
        resolverRecommendation = null,
        strategyRecommendation = null,
        directModeVerdict = null,
        confirmGoodDpiVerdict = null,
        strategyProbeReport =
            strategyProbeReport?.copy(
                recommendation = null,
                completionKind = StrategyProbeCompletionKind.PARTIAL_RESULTS,
                activePathObservation = null,
                domainStrategySeeds = emptyList(),
            ),
        diagnoses =
            diagnoses.filterNot { it.code == NetworkScopeUnverifiedDiagnosis } +
                Diagnosis(
                    code = NetworkScopeUnverifiedDiagnosis,
                    summary = NetworkScopeUnverifiedSummary,
                    severity = "warning",
                ),
    )

internal fun ScanReport.withoutNetworkScopeAuthority(): ScanReport =
    toEngineScanReportWire().withoutNetworkScopeAuthority().toScanReport()

/** Invalid JSON cannot carry actionable evidence and must not escape a failed finalization. */
internal fun PreparedDiagnosticsScan?.scopeReportJson(
    reportJson: String,
    json: Json,
): String? =
    if (this?.networkFingerprint != null && networkScope?.isCurrent() == true) {
        reportJson
    } else {
        runCatching {
            json.encodeToString(
                EngineScanReportWire.serializer(),
                json.decodeEngineScanReportWire(reportJson).withoutNetworkScopeAuthority(),
            )
        }.getOrNull()
    }

/** Revoke staged advice without inserting the measurement rows and native events a second time. */
internal suspend fun revokePersistedNetworkScope(
    sessionId: String,
    scanRecordStore: DiagnosticsScanRecordStore,
    json: Json,
) {
    val session = scanRecordStore.getScanSession(sessionId) ?: return
    val report = session.reportJson?.let { json.decodeEngineScanReportWire(it).withoutNetworkScopeAuthority() }
    val scopedSession =
        session.copy(
            summary =
                if (session.hasAuthoritativeManualConflictCancellation()) {
                    session.summary
                } else {
                    NetworkScopeUnverifiedSummary
                },
            reportJson = report?.let { json.encodeToString(EngineScanReportWire.serializer(), it) },
            reportCompletionKind = report?.completionKind?.name ?: session.reportCompletionKind,
        )
    val results = scanRecordStore.getProbeResults(sessionId)
    if (results.any { it.probeType == "selective_availability_summary" }) {
        scanRecordStore.persistCompletedScan(
            scopedSession,
            results.map { result ->
                if (result.probeType == "selective_availability_summary") {
                    val details =
                        runCatching {
                            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), result.detailJson)
                        }.getOrDefault(emptyList())
                    result.copy(
                        outcome = "matrix_inconclusive",
                        detailJson =
                            json.encodeToString(
                                ListSerializer(ProbeDetail.serializer()),
                                details.filterNot { it.key == "reason" } +
                                    ProbeDetail("reason", "network_scope_unverified"),
                            ),
                    )
                } else {
                    result
                }
            },
        )
    } else {
        scanRecordStore.upsertScanSession(scopedSession)
    }
}
