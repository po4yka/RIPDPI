package com.poyka.ripdpi.ui.screens.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.ConnectivityActivePathCoverage
import com.poyka.ripdpi.diagnostics.ConnectivityAssessment
import com.poyka.ripdpi.diagnostics.ConnectivityAssessmentCode
import com.poyka.ripdpi.diagnostics.ConnectivityStageOutcome
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class StatusConnectivityComparisonUiModel(
    val verdict: String,
    val confidence: String,
    val coverage: String,
    val recoveryLabel: String,
    val recoveredTargets: ImmutableList<String>,
    val stageRows: ImmutableList<StatusAnalysisLabeledRow>,
)

internal fun ConnectivityAssessment.toStatusConnectivityComparison(
    strings: StringResolver,
): StatusConnectivityComparisonUiModel? {
    val comparison = pathComparison ?: return null
    val direct = strings.getString(R.string.status_path_direct)
    val active =
        strings.getString(
            if (comparison.activePathIsVpn) R.string.logs_mode_vpn else R.string.status_path_active,
        )
    val coverageStatus =
        when (comparison.activePathCoverage) {
            ConnectivityActivePathCoverage.COMPLETE -> R.string.status_path_complete
            ConnectivityActivePathCoverage.INCOMPLETE -> R.string.diagnostics_stages_partial
            ConnectivityActivePathCoverage.NOT_RUN -> R.string.status_path_unmeasured
        }
    val rows =
        comparison.stages.map { row ->
            StatusAnalysisLabeledRow(
                label = "${row.target} · ${strings.getString(stageLabel(row.stage))}",
                value =
                    strings.getString(
                        R.string.status_path_stage_pair,
                        direct,
                        strings.getString(row.raw.outcome.label()),
                        row.raw.samples,
                        active,
                        strings.getString(row.inPath.outcome.label()),
                        row.inPath.samples,
                    ),
            )
        } +
            comparison.unmeasuredTargets.map { target ->
                StatusAnalysisLabeledRow(target, strings.getString(R.string.status_path_unmeasured))
            }
    return StatusConnectivityComparisonUiModel(
        verdict = strings.getString(assessmentCode.label()),
        confidence =
            strings.getString(
                when (confidence) {
                    "high" -> R.string.diagnostics_audit_confidence_high
                    "medium" -> R.string.diagnostics_audit_confidence_medium
                    else -> R.string.diagnostics_audit_confidence_low
                },
            ),
        coverage =
            strings.getString(
                R.string.status_path_coverage,
                comparison.completeRawReports,
                comparison.completeRawReports + comparison.incompleteRawReports,
                active,
                strings.getString(coverageStatus),
                comparison.stages.count { it.comparable },
                comparison.stages.size,
            ),
        recoveryLabel = strings.getString(R.string.status_path_http_recovery, active),
        recoveredTargets = comparison.httpRecoveredTargets.toImmutableList(),
        stageRows = rows.toImmutableList(),
    )
}

@Composable
internal fun StatusConnectivityComparisonSection(comparison: StatusConnectivityComparisonUiModel) {
    SettingsRow(
        title = stringResource(R.string.status_path_comparison),
        subtitle = comparison.verdict,
        value = comparison.confidence,
    )
    SettingsRow(title = stringResource(R.string.status_diagnostics_coverage_label), subtitle = comparison.coverage)
    if (comparison.recoveredTargets.isNotEmpty()) {
        SettingsRow(title = comparison.recoveryLabel, subtitle = comparison.recoveredTargets.joinToString())
    }
    comparison.stageRows.forEach { row -> SettingsRow(title = row.label, subtitle = row.value) }
}

private fun ConnectivityAssessmentCode.label(): Int =
    when (this) {
        ConnectivityAssessmentCode.RAW_NETWORK_GENERAL_FAILURE -> R.string.status_path_general_failure
        ConnectivityAssessmentCode.RAW_NETWORK_INTERNATIONAL_FAILURE -> R.string.status_path_external_failure
        ConnectivityAssessmentCode.RAW_NETWORK_SELECTIVE_BLOCKING -> R.string.status_path_selective_failure
        ConnectivityAssessmentCode.VPN_PATH_REGRESSION -> R.string.status_path_regression
        ConnectivityAssessmentCode.RESOLVER_INTERFERENCE -> R.string.status_path_resolver_interference
        ConnectivityAssessmentCode.SERVICE_RUNTIME_FAILURE -> R.string.status_path_service_failure
        ConnectivityAssessmentCode.MIXED_OR_INCONCLUSIVE -> R.string.status_path_inconclusive
    }

private fun ConnectivityStageOutcome.label(): Int =
    when (this) {
        ConnectivityStageOutcome.SUCCEEDED -> R.string.diagnostics_stages_succeeded
        ConnectivityStageOutcome.FAILED -> R.string.diagnostics_stages_failed
        ConnectivityStageOutcome.UNMEASURED -> R.string.status_path_unmeasured
        ConnectivityStageOutcome.CONFLICTING -> R.string.status_path_conflicting
    }

private fun stageLabel(stage: String): Int =
    when (stage) {
        "http" -> R.string.diagnostics_stages_lane_http
        "tls13" -> R.string.diagnostics_stages_lane_tls13
        "tls12" -> R.string.diagnostics_stages_lane_tls12
        "ech" -> R.string.diagnostics_stages_lane_ech
        "quic" -> R.string.diagnostics_stages_lane_quic
        "bootstrap" -> R.string.diagnostics_stages_lane_bootstrap
        "media" -> R.string.diagnostics_stages_lane_media
        "handshake" -> R.string.diagnostics_stages_lane_handshake
        "endpoint" -> R.string.diagnostics_resolver_field_endpoint
        else -> R.string.diagnostics_stages_unknown
    }
