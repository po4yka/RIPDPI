package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.Diagnosis
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

internal fun DiagnosticsUiFactorySupport.toDiagnosisUiModel(diagnosis: Diagnosis): DiagnosticsDiagnosisUiModel {
    val isConfirmGood = diagnosis.code == "confirm_good_dpi_suspected"
    val isConnectionConcurrency = diagnosis.code == "connection_concurrency_interaction"
    val isNetworkUnavailable = diagnosis.code == "network_unavailable"
    return DiagnosticsDiagnosisUiModel(
        code = diagnosis.code,
        summary =
            if (isConfirmGood) {
                context.getString(R.string.diagnostics_confirm_good_dpi_summary)
            } else if (isConnectionConcurrency) {
                context.getString(R.string.diagnostics_connection_concurrency_summary)
            } else if (isNetworkUnavailable) {
                context.getString(R.string.diagnostics_network_unavailable_summary)
            } else if (diagnosis.code == "network_scope_unverified") {
                context.getString(R.string.diagnostics_network_scope_unverified_summary)
            } else {
                diagnosis.summary
            },
        severity = diagnosis.severity,
        target = diagnosis.target,
        tone =
            when (diagnosis.severity.lowercase(Locale.US)) {
                "negative", "error", "blocked" -> DiagnosticsTone.Negative
                "warning", "degraded" -> DiagnosticsTone.Warning
                "positive", "ok" -> DiagnosticsTone.Positive
                else -> DiagnosticsTone.Info
            },
        evidence =
            (
                if (isConfirmGood) {
                    listOf(
                        context.getString(R.string.diagnostics_confirm_good_dpi_evidence_reality),
                        context.getString(R.string.diagnostics_confirm_good_dpi_evidence_quic),
                    )
                } else if (isNetworkUnavailable) {
                    diagnosis.evidence.map { evidence ->
                        if (evidence == "network transport is unavailable") {
                            context.getString(R.string.diagnostics_network_unavailable_evidence)
                        } else {
                            evidence
                        }
                    }
                } else {
                    diagnosis.evidence
                }
            ).toImmutableList(),
        recommendation =
            if (isConfirmGood) {
                context.getString(R.string.diagnostics_confirm_good_dpi_recommendation)
            } else if (isConnectionConcurrency && diagnosis.severity == "warning") {
                context.getString(R.string.diagnostics_connection_concurrency_recommendation)
            } else if (isConnectionConcurrency && diagnosis.recommendation == ConnectionConcurrencyInfoRecommendation) {
                context.getString(R.string.diagnostics_connection_concurrency_info_recommendation)
            } else if (isNetworkUnavailable &&
                diagnosis.recommendation == "Connect to a network and run diagnostics again"
            ) {
                context.getString(R.string.diagnostics_network_unavailable_recommendation)
            } else {
                diagnosis.recommendation
            },
    )
}

private const val ConnectionConcurrencyInfoRecommendation =
    "A confirmed interaction applies the learned owned-stack profile and cap on the next service start; " +
        "browser-originated TLS in proxy mode may bypass this control."
