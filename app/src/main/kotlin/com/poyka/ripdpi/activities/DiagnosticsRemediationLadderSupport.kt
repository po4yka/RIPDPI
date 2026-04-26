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
): DiagnosticsRemediationLadderUiModel = remediationLadder(title, summary, actionLabel, actionKind, null, tone, *steps)

private fun remediationLadder(
    title: String,
    summary: String,
    actionLabel: String,
    actionKind: DiagnosticsRemediationActionKindUiModel,
    actionTargetUrl: String?,
    tone: DiagnosticsTone,
    vararg steps: String,
): DiagnosticsRemediationLadderUiModel =
    DiagnosticsRemediationLadderUiModel(
        title = title,
        summary = summary,
        steps = remediationSteps(*steps),
        primaryAction =
            DiagnosticsRemediationActionUiModel(
                label = actionLabel,
                kind = actionKind,
                targetUrl = actionTargetUrl,
            ),
        tone = tone,
    )

private fun DiagnosticsUiFactorySupport.scanTransportRemediationLadder(
    latestSession: DiagnosticsSessionRowUiModel,
): DiagnosticsRemediationLadderUiModel? =
    when (
        recommendTransportRemediation(
            result = latestSession.directModeResult,
            reasonCode = latestSession.directModeReasonCode,
            transportClass = latestSession.directTransportClass,
        )
    ) {
        TransportRemediationKind.OWNED_STACK_ACTION -> {
            latestSession.ownedStackLaunchUrl?.let { targetUrl ->
                remediationLadder(
                    title = context.getString(R.string.diagnostics_remediation_owned_stack_title),
                    summary = context.getString(R.string.diagnostics_remediation_owned_stack_summary),
                    actionLabel = context.getString(R.string.diagnostics_remediation_open_owned_stack_action),
                    actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER,
                    actionTargetUrl = targetUrl,
                    tone = DiagnosticsTone.Warning,
                    context.getString(R.string.diagnostics_remediation_owned_stack_step_open_browser),
                    context.getString(R.string.diagnostics_remediation_owned_stack_step_verify_result),
                    context.getString(R.string.diagnostics_remediation_owned_stack_step_android17_note),
                )
            }
        }

        TransportRemediationKind.BROWSER_FALLBACK -> {
            remediationLadder(
                title = context.getString(R.string.diagnostics_remediation_browser_relay_title),
                summary = context.getString(R.string.diagnostics_remediation_browser_relay_summary),
                actionLabel = context.getString(R.string.diagnostics_remediation_open_mode_editor_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR,
                tone = DiagnosticsTone.Warning,
                context.getString(R.string.diagnostics_remediation_browser_relay_step_open_mode_editor),
                context.getString(R.string.diagnostics_remediation_browser_relay_step_enable_relay),
                context.getString(R.string.diagnostics_remediation_browser_relay_step_choose_preset),
            )
        }

        TransportRemediationKind.QUIC_FALLBACK -> {
            remediationLadder(
                title = context.getString(R.string.diagnostics_remediation_quic_relay_title),
                summary = context.getString(R.string.diagnostics_remediation_quic_relay_summary),
                actionLabel = context.getString(R.string.diagnostics_remediation_open_mode_editor_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR,
                tone = DiagnosticsTone.Warning,
                context.getString(R.string.diagnostics_remediation_quic_relay_step_open_mode_editor),
                context.getString(R.string.diagnostics_remediation_quic_relay_step_enable_relay),
                context.getString(R.string.diagnostics_remediation_quic_relay_step_choose_preset),
            )
        }

        TransportRemediationKind.NO_RELIABLE_RELAY_HINT -> {
            remediationLadder(
                title = context.getString(R.string.diagnostics_remediation_no_hint_title),
                summary = context.getString(R.string.diagnostics_remediation_no_hint_summary),
                actionLabel = context.getString(R.string.diagnostics_remediation_open_history_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY,
                tone = DiagnosticsTone.Warning,
                context.getString(R.string.diagnostics_remediation_no_hint_step_open_history),
                context.getString(R.string.diagnostics_remediation_no_hint_step_review_evidence),
                context.getString(R.string.diagnostics_remediation_no_hint_step_retry),
            )
        }

        null -> {
            null
        }
    }

private fun StringResolver.homeTransportRemediationLadder(
    latestOutcome: HomeDiagnosticsLatestAuditUiState,
): DiagnosticsRemediationLadderUiModel? =
    when (
        recommendTransportRemediation(
            result = latestOutcome.directModeResult,
            reasonCode = latestOutcome.directModeReasonCode,
            transportClass = latestOutcome.directTransportClass,
            evidence = latestOutcome.transportRemediationEvidence,
        )
    ) {
        TransportRemediationKind.OWNED_STACK_ACTION -> {
            remediationLadder(
                title = getString(R.string.home_remediation_owned_stack_title),
                summary = getString(R.string.home_remediation_owned_stack_summary),
                actionLabel = getString(R.string.home_remediation_open_diagnostics_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS,
                tone = DiagnosticsTone.Warning,
                getString(R.string.home_remediation_owned_stack_step_open_diagnostics),
                getString(R.string.home_remediation_owned_stack_step_open_browser),
                getString(R.string.home_remediation_owned_stack_step_verify_result),
            )
        }

        TransportRemediationKind.BROWSER_FALLBACK -> {
            remediationLadder(
                title = getString(R.string.home_remediation_browser_relay_title),
                summary = getString(R.string.home_remediation_browser_relay_summary),
                actionLabel = getString(R.string.diagnostics_remediation_open_mode_editor_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR,
                tone = DiagnosticsTone.Warning,
                getString(R.string.home_remediation_browser_relay_step_open_mode_editor),
                getString(R.string.home_remediation_browser_relay_step_enable_relay),
                getString(R.string.home_remediation_browser_relay_step_choose_preset),
            )
        }

        TransportRemediationKind.QUIC_FALLBACK -> {
            remediationLadder(
                title = getString(R.string.home_remediation_quic_relay_title),
                summary = getString(R.string.home_remediation_quic_relay_summary),
                actionLabel = getString(R.string.diagnostics_remediation_open_mode_editor_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR,
                tone = DiagnosticsTone.Warning,
                getString(R.string.home_remediation_quic_relay_step_open_mode_editor),
                getString(R.string.home_remediation_quic_relay_step_enable_relay),
                getString(R.string.home_remediation_quic_relay_step_choose_preset),
            )
        }

        TransportRemediationKind.NO_RELIABLE_RELAY_HINT -> {
            remediationLadder(
                title = getString(R.string.home_remediation_no_hint_title),
                summary = getString(R.string.home_remediation_no_hint_summary),
                actionLabel = getString(R.string.home_remediation_open_diagnostics_action),
                actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS,
                tone = DiagnosticsTone.Warning,
                getString(R.string.home_remediation_no_hint_step_open_diagnostics),
                getString(R.string.home_remediation_no_hint_step_review_evidence),
                getString(R.string.home_remediation_no_hint_step_retry),
            )
        }

        null -> {
            null
        }
    }

internal fun DiagnosticsUiFactorySupport.buildScanRemediationLadder(
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    workflowRestriction: DiagnosticsWorkflowRestrictionUiModel?,
    resolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    strategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    latestSession: DiagnosticsSessionRowUiModel?,
): DiagnosticsRemediationLadderUiModel? =
    scanWorkflowRestrictionLadder(workflowRestriction)
        ?: scanProbeBasedLadder(selectedProfile, resolverRecommendation, strategyProbeReport, latestSession)

private fun DiagnosticsUiFactorySupport.scanWorkflowRestrictionLadder(
    workflowRestriction: DiagnosticsWorkflowRestrictionUiModel?,
): DiagnosticsRemediationLadderUiModel? =
    workflowRestriction?.let { restriction ->
        when (restriction.reason) {
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

private fun DiagnosticsUiFactorySupport.scanProbeBasedLadder(
    selectedProfile: DiagnosticsProfileOptionUiModel?,
    resolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
    strategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    latestSession: DiagnosticsSessionRowUiModel?,
): DiagnosticsRemediationLadderUiModel? {
    val report = strategyProbeReport ?: return null
    val isFullAudit = selectedProfile?.isFullAudit == true
    return scanDnsLadder(report, resolverRecommendation)
        ?: scanPartialResultsLadder(report)
        ?: latestSession?.let { scanTransportRemediationLadder(it) }
        ?: scanReviewLadder(isFullAudit, report, latestSession)
}

private fun DiagnosticsUiFactorySupport.scanDnsLadder(
    report: DiagnosticsStrategyProbeReportUiModel,
    resolverRecommendation: DiagnosticsResolverRecommendationUiModel?,
): DiagnosticsRemediationLadderUiModel? {
    if (report.completionKind != StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED || resolverRecommendation == null) {
        return null
    }
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

private fun DiagnosticsUiFactorySupport.scanPartialResultsLadder(
    report: DiagnosticsStrategyProbeReportUiModel,
): DiagnosticsRemediationLadderUiModel? {
    if (report.completionKind != StrategyProbeCompletionKind.PARTIAL_RESULTS) return null
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

private fun DiagnosticsUiFactorySupport.scanReviewLadder(
    isFullAudit: Boolean,
    report: DiagnosticsStrategyProbeReportUiModel,
    latestSession: DiagnosticsSessionRowUiModel?,
): DiagnosticsRemediationLadderUiModel? {
    if (!isFullAudit || report.winningPath != null || latestSession == null) return null
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

internal fun StringResolver.buildHomeRemediationLadder(
    commandLineBlocked: Boolean,
    fingerprintMismatch: Boolean,
    latestOutcome: HomeDiagnosticsLatestAuditUiState?,
): DiagnosticsRemediationLadderUiModel? =
    homeCommandLineLadder(commandLineBlocked)
        ?: latestOutcome?.let { homeOutcomeLadder(fingerprintMismatch, it) }

private fun StringResolver.homeCommandLineLadder(commandLineBlocked: Boolean): DiagnosticsRemediationLadderUiModel? {
    if (!commandLineBlocked) return null
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

private fun StringResolver.homeOutcomeLadder(
    fingerprintMismatch: Boolean,
    outcome: HomeDiagnosticsLatestAuditUiState,
): DiagnosticsRemediationLadderUiModel? =
    homeStaleOrMismatchLadder(fingerprintMismatch, outcome)
        ?: homeTransportRemediationLadder(outcome)
        ?: homeNotActionableLadder(outcome)

private fun StringResolver.homeStaleOrMismatchLadder(
    fingerprintMismatch: Boolean,
    outcome: HomeDiagnosticsLatestAuditUiState,
): DiagnosticsRemediationLadderUiModel? {
    if (!fingerprintMismatch && !outcome.stale) return null
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

private fun StringResolver.homeNotActionableLadder(
    outcome: HomeDiagnosticsLatestAuditUiState,
): DiagnosticsRemediationLadderUiModel? {
    if (outcome.actionable) return null
    val needsHistoryReview = outcome.failedStageCount > 0 || outcome.completedStageCount < outcome.totalStageCount
    return if (needsHistoryReview) {
        remediationLadder(
            title = getString(R.string.home_remediation_review_title),
            summary = outcome.summary,
            actionLabel = getString(R.string.diagnostics_remediation_open_history_action),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY,
            tone = DiagnosticsTone.Warning,
            getString(R.string.home_remediation_review_step_open_history),
            getString(R.string.home_remediation_review_step_compare_results),
            getString(R.string.home_remediation_review_step_rerun),
        )
    } else {
        remediationLadder(
            title = getString(R.string.home_remediation_no_actionable_title),
            summary = getString(R.string.home_diagnostics_no_actionable_result),
            actionLabel = getString(R.string.home_remediation_open_diagnostics_action),
            actionKind = DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS,
            tone = DiagnosticsTone.Warning,
            getString(R.string.home_remediation_no_actionable_step_open_diagnostics),
            getString(R.string.home_remediation_no_actionable_step_review),
            getString(R.string.home_remediation_no_actionable_step_rerun),
        )
    }
}
