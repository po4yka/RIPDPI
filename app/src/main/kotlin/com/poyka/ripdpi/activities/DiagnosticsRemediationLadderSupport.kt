package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.platform.StringResolver
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private fun remediationSteps(vararg steps: String): ImmutableList<DiagnosticsRemediationStepUiModel> =
    steps
        .filter { it.isNotBlank() }
        .map(::DiagnosticsRemediationStepUiModel)
        .toImmutableList()

private fun remediationLadder(
    title: String,
    summary: String,
    actionLabel: String,
    actionKind: DiagnosticsRemediationActionKindUiModel,
    tone: DiagnosticsTone,
    vararg steps: String,
): DiagnosticsRemediationLadderUiModel =
    DiagnosticsRemediationLadderUiModel(
        title = title,
        summary = summary,
        steps = remediationSteps(*steps),
        primaryAction = DiagnosticsRemediationActionUiModel(label = actionLabel, kind = actionKind),
        tone = tone,
    )

internal fun DiagnosticsUiFactorySupport.buildScanRemediationLadder(
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    workflowRestriction: DiagnosticsWorkflowRestrictionUiModel?,
    resolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    strategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    latestSession: DiagnosticsSessionRowUiModel?,
): DiagnosticsRemediationLadderUiModel? {
    workflowRestriction?.let { restriction ->
        return when (restriction.reason) {
            DiagnosticsWorkflowRestrictionReasonUiModel.COMMAND_LINE_MODE_ACTIVE -> {
                remediationLadder(
                    title = context.getString(R.string.diagnostics_remediation_command_line_title),
                    summary = restriction.body,
                    actionLabel = context.getString(R.string.diagnostics_scan_open_advanced_settings),
                    actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS,
                    tone = DiagnosticsTone.Negative,
                    context.getString(R.string.diagnostics_remediation_command_line_step_open_settings),
                    context.getString(R.string.diagnostics_remediation_command_line_step_disable_setting),
                    context.getString(R.string.diagnostics_remediation_command_line_step_retry),
                )
            }

            DiagnosticsWorkflowRestrictionReasonUiModel.VPN_PERMISSION_DISABLED -> {
                remediationLadder(
                    title = context.getString(R.string.diagnostics_remediation_vpn_permission_title),
                    summary = restriction.body,
                    actionLabel = context.getString(R.string.diagnostics_scan_grant_vpn_permission),
                    actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_VPN_PERMISSION,
                    tone = DiagnosticsTone.Warning,
                    context.getString(R.string.diagnostics_remediation_vpn_permission_step_grant),
                    context.getString(R.string.diagnostics_remediation_vpn_permission_step_retry),
                )
            }
        }
    }

    val report = strategyProbeReport ?: return null
    val isFullAudit = selectedProfile?.isFullAudit == true

    if (report.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED && resolverRecommendation != null) {
        return remediationLadder(
            title = context.getString(R.string.diagnostics_remediation_dns_title),
            summary = resolverRecommendation.rationale,
            actionLabel = context.getString(R.string.diagnostics_remediation_open_dns_settings_action),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_DNS_SETTINGS,
            tone = DiagnosticsTone.Warning,
            context.getString(R.string.diagnostics_remediation_dns_step_open_settings),
            context.getString(
                R.string.diagnostics_remediation_dns_step_review_recommendation,
                resolverRecommendation.headline,
            ),
            context.getString(R.string.diagnostics_remediation_dns_step_retry),
        )
    }

    if (report.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS) {
        return remediationLadder(
            title = context.getString(R.string.diagnostics_remediation_partial_title),
            summary = context.getString(R.string.diagnostics_remediation_partial_summary),
            actionLabel = context.getString(R.string.diagnostics_remediation_open_history_action),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY,
            tone = DiagnosticsTone.Warning,
            context.getString(R.string.diagnostics_remediation_partial_step_open_history),
            context.getString(R.string.diagnostics_remediation_partial_step_review),
            context.getString(R.string.diagnostics_remediation_partial_step_retry),
        )
    }

    if (isFullAudit && report.winningPath == null && latestSession != null) {
        return remediationLadder(
            title = context.getString(R.string.diagnostics_remediation_review_title),
            summary = latestSession.summary,
            actionLabel = context.getString(R.string.diagnostics_remediation_open_history_action),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY,
            tone = DiagnosticsTone.Warning,
            context.getString(R.string.diagnostics_remediation_review_step_open_history),
            context.getString(R.string.diagnostics_remediation_review_step_compare_results),
            context.getString(R.string.diagnostics_remediation_review_step_rerun),
        )
    }

    return null
}

internal fun StringResolver.buildHomeRemediationLadder(
    commandLineBlocked: Boolean,
    fingerprintMismatch: Boolean,
    latestOutcome: HomeDiagnosticsLatestAuditUiState?,
): DiagnosticsRemediationLadderUiModel? {
    if (commandLineBlocked) {
        return remediationLadder(
            title = getString(R.string.home_remediation_command_line_title),
            summary = getString(R.string.home_diagnostics_command_line_blocked),
            actionLabel = getString(R.string.diagnostics_scan_open_advanced_settings),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS,
            tone = DiagnosticsTone.Negative,
            getString(R.string.home_remediation_command_line_step_open_settings),
            getString(R.string.home_remediation_command_line_step_disable_setting),
            getString(R.string.home_remediation_command_line_step_retry),
        )
    }

    val outcome = latestOutcome ?: return null
    if (fingerprintMismatch || outcome.stale) {
        return remediationLadder(
            title = getString(R.string.home_remediation_stale_title),
            summary = getString(R.string.home_diagnostics_run_again),
            actionLabel = getString(R.string.home_remediation_open_diagnostics_action),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS,
            tone = DiagnosticsTone.Warning,
            getString(R.string.home_remediation_stale_step_open_diagnostics),
            getString(R.string.home_remediation_stale_step_review),
            getString(R.string.home_remediation_stale_step_rerun),
        )
    }

    if (!outcome.actionable) {
        val needsHistoryReview = outcome.failedStageCount > 0 || outcome.completedStageCount < outcome.totalStageCount
        val steps =
            if (needsHistoryReview) {
                arrayOf(
                    getString(R.string.home_remediation_review_step_open_history),
                    getString(R.string.home_remediation_review_step_compare_results),
                    getString(R.string.home_remediation_review_step_rerun),
                )
            } else {
                arrayOf(
                    getString(R.string.home_remediation_no_actionable_step_open_diagnostics),
                    getString(R.string.home_remediation_no_actionable_step_review),
                    getString(R.string.home_remediation_no_actionable_step_rerun),
                )
            }
        return remediationLadder(
            title =
                if (needsHistoryReview) {
                    getString(R.string.home_remediation_review_title)
                } else {
                    getString(R.string.home_remediation_no_actionable_title)
                },
            summary =
                if (needsHistoryReview) {
                    outcome.summary
                } else {
                    getString(R.string.home_diagnostics_no_actionable_result)
                },
            actionLabel =
                if (needsHistoryReview) {
                    getString(R.string.diagnostics_remediation_open_history_action)
                } else {
                    getString(R.string.home_remediation_open_diagnostics_action)
                },
            actionKind =
                if (needsHistoryReview) {
                    DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY
                } else {
                    DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS
                },
            tone = DiagnosticsTone.Warning,
            *steps,
        )
    }

    return null
}
