package com.poyka.ripdpi.diagnostics

internal data class DiagnosticsPostScanHealthSummary(
    val scanFinalized: Boolean,
    val resultPayloadPresent: Boolean,
    val nativeReportVersionPresent: Boolean,
    val strategyProbeFinalized: Boolean,
    val healthState: DiagnosticsPostScanHealthState,
    val summary: String,
)

internal enum class DiagnosticsPostScanHealthState {
    HEALTHY,
    PARTIAL,
    INCONCLUSIVE,
}

internal fun ScanReport.postScanHealthSummary(): DiagnosticsPostScanHealthSummary {
    val scanFinalized = finishedAt >= startedAt && summary != ScanCancelledSummary
    val resultPayloadPresent = results.isNotEmpty() || observations.isNotEmpty() || strategyProbeReport != null
    val nativeReportVersionPresent = !engineAnalysisVersion.isNullOrBlank() || !classifierVersion.isNullOrBlank()
    val strategyProbeFinalized =
        strategyProbeReport?.completionKind?.let { completion ->
            completion != StrategyProbeCompletionKind.PARTIAL_RESULTS
        } ?: true
    val state =
        when {
            scanFinalized && resultPayloadPresent && strategyProbeFinalized -> DiagnosticsPostScanHealthState.HEALTHY
            resultPayloadPresent -> DiagnosticsPostScanHealthState.PARTIAL
            else -> DiagnosticsPostScanHealthState.INCONCLUSIVE
        }
    return DiagnosticsPostScanHealthSummary(
        scanFinalized = scanFinalized,
        resultPayloadPresent = resultPayloadPresent,
        nativeReportVersionPresent = nativeReportVersionPresent,
        strategyProbeFinalized = strategyProbeFinalized,
        healthState = state,
        summary = state.summaryLine(),
    )
}

private fun DiagnosticsPostScanHealthState.summaryLine(): String =
    when (this) {
        DiagnosticsPostScanHealthState.HEALTHY -> "Scan finalized and report payload was persisted cleanly"
        DiagnosticsPostScanHealthState.PARTIAL -> "Scan produced a partial report payload"
        DiagnosticsPostScanHealthState.INCONCLUSIVE -> "Scan health could not be confirmed from report payload"
    }
