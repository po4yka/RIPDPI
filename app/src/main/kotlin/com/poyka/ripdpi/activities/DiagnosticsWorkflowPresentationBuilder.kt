package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import kotlinx.collections.immutable.toImmutableList

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun DiagnosticsUiFactorySupport.buildWorkflowPresentation(
    profile: DiagnosticsProfileOptionUiModel,
    scanIsBusy: Boolean,
    runRawEnabled: Boolean,
    strategyProbeSelected: Boolean,
    strategyProbeReport: DiagnosticsStrategyProbeReportUiModel?,
    workflowRestriction: DiagnosticsWorkflowRestrictionUiModel?,
): DiagnosticsScanWorkflowPresentationUiModel {
    val isFullAudit = profile.isFullAudit
    val status =
        when {
            scanIsBusy && isFullAudit -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_audit_progress_title),
                    body = context.getString(R.string.diagnostics_profile_audit_running_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            scanIsBusy && strategyProbeSelected -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_probe_progress_title),
                    body = context.getString(R.string.diagnostics_profile_probe_running_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            scanIsBusy -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_status_running),
                    body = context.getString(R.string.diagnostics_profile_connectivity_running_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            isFullAudit && !runRawEnabled -> {
                WorkflowStatus(
                    title =
                        workflowRestriction?.title
                            ?: context.getString(R.string.diagnostics_audit_unavailable_title),
                    body =
                        workflowRestriction?.body
                            ?: context.getString(R.string.diagnostics_profile_audit_unavailable_body),
                    tone = DiagnosticsTone.Negative,
                )
            }

            strategyProbeSelected && !runRawEnabled -> {
                WorkflowStatus(
                    title =
                        workflowRestriction?.title
                            ?: context.getString(R.string.diagnostics_probe_unavailable_title),
                    body =
                        workflowRestriction?.body
                            ?: context.getString(R.string.diagnostics_profile_probe_unavailable_body),
                    tone = DiagnosticsTone.Negative,
                )
            }

            isFullAudit && strategyProbeReport?.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_audit_short_circuit_title),
                    body = context.getString(R.string.diagnostics_profile_audit_short_circuit_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            isFullAudit && strategyProbeReport?.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
                val (executed, planned) = strategyProbeReport.executedAndPlannedCounts
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_audit_partial_results_title),
                    body =
                        context.getString(
                            R.string.diagnostics_profile_audit_partial_results_body,
                            executed,
                            planned,
                        ),
                    tone = DiagnosticsTone.Warning,
                )
            }

            isFullAudit && strategyProbeReport != null -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_audit_ready_title),
                    body = context.getString(R.string.diagnostics_profile_audit_ready_body),
                    tone = DiagnosticsTone.Positive,
                )
            }

            strategyProbeSelected &&
                strategyProbeReport?.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_probe_short_circuit_title),
                    body = context.getString(R.string.diagnostics_profile_probe_short_circuit_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            strategyProbeSelected &&
                strategyProbeReport?.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
                val (executed, planned) = strategyProbeReport.executedAndPlannedCounts
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_probe_partial_results_title),
                    body =
                        context.getString(
                            R.string.diagnostics_profile_probe_partial_results_body,
                            executed,
                            planned,
                        ),
                    tone = DiagnosticsTone.Warning,
                )
            }

            strategyProbeSelected && strategyProbeReport != null -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_probe_ready_title),
                    body = context.getString(R.string.diagnostics_profile_probe_ready_body),
                    tone = DiagnosticsTone.Positive,
                )
            }

            isFullAudit -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_audit_profile_title),
                    body = context.getString(R.string.diagnostics_profile_audit_body),
                    tone = DiagnosticsTone.Neutral,
                )
            }

            strategyProbeSelected -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_probe_profile_title),
                    body = context.getString(R.string.diagnostics_profile_probe_body),
                    tone = DiagnosticsTone.Neutral,
                )
            }

            else -> {
                WorkflowStatus(
                    title = context.getString(R.string.diagnostics_profile_connectivity_title),
                    body = context.getString(R.string.diagnostics_profile_connectivity_body),
                    tone = DiagnosticsTone.Neutral,
                )
            }
        }
    return DiagnosticsScanWorkflowPresentationUiModel(
        title = status.title,
        body = status.body,
        tone = status.tone,
        badges = buildWorkflowBadges(profile, strategyProbeSelected, isFullAudit).toImmutableList(),
        rawActionLabel =
            when {
                !runRawEnabled && isFullAudit -> context.getString(R.string.diagnostics_action_audit_unavailable)
                !runRawEnabled -> context.getString(R.string.diagnostics_action_probe_unavailable)
                isFullAudit -> context.getString(R.string.diagnostics_action_start_audit)
                strategyProbeSelected -> context.getString(R.string.diagnostics_action_start_probe)
                else -> context.getString(R.string.diagnostics_action_raw)
            },
        inPathActionLabel = context.getString(R.string.diagnostics_action_in_path),
    )
}

private data class WorkflowStatus(
    val title: String,
    val body: String,
    val tone: DiagnosticsTone,
)

private val DiagnosticsStrategyProbeReportUiModel.executedAndPlannedCounts: Pair<Int, Int>
    get() {
        val coverage = auditAssessment?.coverage
        val executed = (coverage?.tcpCandidatesExecuted ?: 0) + (coverage?.quicCandidatesExecuted ?: 0)
        val planned = (coverage?.tcpCandidatesPlanned ?: 0) + (coverage?.quicCandidatesPlanned ?: 0)
        return executed to planned
    }
