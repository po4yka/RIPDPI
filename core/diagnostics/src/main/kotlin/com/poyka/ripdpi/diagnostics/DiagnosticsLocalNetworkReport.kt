package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.engine.ScanCompletionKind
import kotlinx.serialization.json.Json

/** App preflight outcomes are capability omissions, never native probe execution or DPI evidence. */
internal fun EngineScanReportWire.withLocalNetworkDeferrals(prepared: PreparedDiagnosticsScan): EngineScanReportWire {
    if (prepared.localNetworkDeferrals.isEmpty()) return this
    return copy(
        results = results + prepared.localNetworkDeferrals.map(ProbeResult::toEngineProbeResultWire),
        completionKind =
            if (completionKind == ScanCompletionKind.NORMAL) {
                ScanCompletionKind.PARTIAL_RESULTS
            } else {
                completionKind
            },
        // Recommendations cannot claim coverage of the original, unexecuted target set.
        directModeVerdict = null,
        strategyRecommendation = null,
    )
}

internal fun String.withLocalNetworkDeferrals(
    prepared: PreparedDiagnosticsScan,
    json: Json,
): String =
    if (prepared.localNetworkDeferrals.isEmpty()) {
        this
    } else {
        val report = json.decodeEngineScanReportWire(this)
        val deferred = prepared.localNetworkDeferrals.map(ProbeResult::toEngineProbeResultWire)
        if (deferred.all(report.results::contains)) {
            this
        } else {
            val missingPrepared =
                prepared.copy(
                    localNetworkDeferrals =
                        prepared.localNetworkDeferrals.filter { deferral ->
                            deferral.toEngineProbeResultWire() !in report.results
                        },
                )
            json.encodeToString(
                EngineScanReportWire.serializer(),
                report.withLocalNetworkDeferrals(missingPrepared),
            )
        }
    }
