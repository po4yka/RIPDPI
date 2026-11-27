package com.poyka.ripdpi.ui.screens.diagnostics.state

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.Diagnosis
import com.poyka.ripdpi.diagnostics.MobileAllowlistDiagnosis
import com.poyka.ripdpi.diagnostics.QuicInitialUnobservedDiagnosis
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

internal fun DiagnosticsUiFactorySupport.toDiagnosisUiModel(diagnosis: Diagnosis): DiagnosticsDiagnosisUiModel {
    val isConfirmGood = diagnosis.code == "confirm_good_dpi_suspected"
    val isConnectionConcurrency = diagnosis.code == "connection_concurrency_interaction"
    val isNetworkUnavailable = diagnosis.code == "network_unavailable"
    return DiagnosticsDiagnosisUiModel(
        code = diagnosis.code,
        summary = LocalizedDiagnosisSummaries[diagnosis.code]?.let(context::getString) ?: diagnosis.summary,
        controlStatus = context.getString(diagnosticControlStatusResource(diagnosis.controlValidated)),
        measurementLimit =
            if (diagnosis.code == QuicInitialUnobservedDiagnosis) {
                context.getString(R.string.diagnostics_quic_initial_limit)
            } else {
                null
            },
        severity = diagnosis.severity,
        target = diagnosis.target,
        tone = diagnosisTone(diagnosis.severity),
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
            } else if (diagnosis.code == MobileAllowlistDiagnosis) {
                context.getString(R.string.diagnostics_mobile_allowlist_recommendation)
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

private fun diagnosisTone(severity: String): DiagnosticsTone =
    when (severity.lowercase(Locale.US)) {
        "negative", "error", "blocked" -> DiagnosticsTone.Negative
        "warning", "degraded" -> DiagnosticsTone.Warning
        "positive", "ok" -> DiagnosticsTone.Positive
        else -> DiagnosticsTone.Info
    }

internal fun diagnosticControlStatusResource(value: Boolean?): Int =
    when (value) {
        true -> R.string.diagnostics_control_passed
        false -> R.string.diagnostics_control_failed
        null -> R.string.diagnostics_control_unknown
    }

private const val ConnectionConcurrencyInfoRecommendation =
    "A confirmed interaction applies the learned owned-stack profile and cap on the next service start; " +
        "browser-originated TLS in proxy mode may bypass this control."

private val LocalizedDiagnosisSummaries =
    mapOf(
        "confirm_good_dpi_suspected" to R.string.diagnostics_confirm_good_dpi_summary,
        "connection_concurrency_interaction" to R.string.diagnostics_connection_concurrency_summary,
        "network_unavailable" to R.string.diagnostics_network_unavailable_summary,
        "network_scope_unverified" to R.string.diagnostics_network_scope_unverified_summary,
        MobileAllowlistDiagnosis to R.string.diagnostics_mobile_allowlist_summary,
    )
