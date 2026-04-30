package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.CompletedProbeUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanWorkflowBadgeUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanWorkflowPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeProgressLaneUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsWorkflowRestrictionActionKindUiModel
import com.poyka.ripdpi.activities.DnsBaselineStatus
import com.poyka.ripdpi.activities.DpiFailureClass
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.activities.ScanNetworkContextUiModel
import com.poyka.ripdpi.activities.StrategyCandidateTimelineEntryUiModel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricPill
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricSurface
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.indicators.ripDpiMetricToneStyle
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun DiagnosticsScanWorkflowCard(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("DiagnosticsScanWorkflowCard")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val presentation =
        scan.workflowPresentation
            ?: workflowPresentationFallback(scan, profile, strategyProbeSelected, isFullAudit)

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(label = presentation.title, tone = statusTone(presentation.tone))
        Text(
            text = profile.name,
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        Text(
            text = presentation.body,
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(
                items = presentation.badges,
                key = { it.text },
                contentType = { "workflow_badge" },
            ) { badge ->
                EventBadge(text = badge.text, tone = badge.tone)
            }
        }
        scan.selectedProfileScopeLabel?.let { label ->
            Text(
                text = label,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
        scan.remediationLadder?.let { ladder ->
            DiagnosticsRemediationLadderCard(
                ladder = ladder,
                onAction = { action ->
                    when (action.kind) {
                        DiagnosticsRemediationActionKindUiModel.OPEN_ADVANCED_SETTINGS -> {
                            onOpenAdvancedSettings()
                        }

                        DiagnosticsRemediationActionKindUiModel.OPEN_VPN_PERMISSION -> {
                            onRequestVpnPermission()
                        }

                        DiagnosticsRemediationActionKindUiModel.OPEN_DNS_SETTINGS -> {
                            onOpenDnsSettings()
                        }

                        DiagnosticsRemediationActionKindUiModel.OPEN_HISTORY -> {
                            onOpenHistory()
                        }

                        DiagnosticsRemediationActionKindUiModel.OPEN_MODE_EDITOR -> {
                            onOpenModeEditor()
                        }

                        DiagnosticsRemediationActionKindUiModel.OPEN_OWNED_STACK_BROWSER -> {
                            action.targetUrl?.let(onOpenOwnedStackBrowser)
                        }

                        DiagnosticsRemediationActionKindUiModel.OPEN_DIAGNOSTICS -> {
                            Unit
                        }
                    }
                },
                cardTestTag = RipDpiTestTags.DiagnosticsRemediationLadderCard,
                actionTestTag = RipDpiTestTags.DiagnosticsRemediationLadderAction,
            )
        }
        scan.runRawHint?.let { hint ->
            WarningBanner(
                title =
                    if (isFullAudit) {
                        stringResource(R.string.diagnostics_audit_profile_title)
                    } else {
                        stringResource(R.string.diagnostics_probe_profile_title)
                    },
                message = hint,
                tone =
                    if (scan.runRawEnabled) {
                        WarningBannerTone.Info
                    } else {
                        WarningBannerTone.Restricted
                    },
            )
        }
        scan.runInPathHint?.let { hint ->
            WarningBanner(
                title = stringResource(R.string.diagnostics_probe_path_title),
                message = hint,
                tone = WarningBannerTone.Restricted,
            )
        }
        WorkflowActionRow(
            strategyProbeSelected = strategyProbeSelected,
            scan = scan,
            spacing = spacing.sm,
            presentation = presentation,
            onRunRawScan = onRunRawScan,
            onRunInPathScan = onRunInPathScan,
        )
        if (scan.isBusy) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_action_cancel),
                onClick = onCancelScan,
                variant = RipDpiButtonVariant.Destructive,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanCancelAction),
            )
        }
    }
}

private data class WorkflowStatusUiModel(
    val title: String,
    val body: String,
    val tone: DiagnosticsTone,
)

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun workflowPresentationFallback(
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): DiagnosticsScanWorkflowPresentationUiModel {
    val status =
        when {
            scan.isBusy && isFullAudit -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_audit_progress_title),
                    body = stringResource(R.string.diagnostics_profile_audit_running_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            scan.isBusy && strategyProbeSelected -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_probe_progress_title),
                    body = stringResource(R.string.diagnostics_profile_probe_running_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            scan.isBusy -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_status_running),
                    body = stringResource(R.string.diagnostics_profile_connectivity_running_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            isFullAudit && !scan.runRawEnabled -> {
                WorkflowStatusUiModel(
                    title =
                        scan.workflowRestriction?.title
                            ?: stringResource(R.string.diagnostics_audit_unavailable_title),
                    body =
                        scan.workflowRestriction?.body
                            ?: stringResource(R.string.diagnostics_profile_audit_unavailable_body),
                    tone = DiagnosticsTone.Negative,
                )
            }

            strategyProbeSelected && !scan.runRawEnabled -> {
                WorkflowStatusUiModel(
                    title =
                        scan.workflowRestriction?.title
                            ?: stringResource(R.string.diagnostics_probe_unavailable_title),
                    body =
                        scan.workflowRestriction?.body
                            ?: stringResource(R.string.diagnostics_profile_probe_unavailable_body),
                    tone = DiagnosticsTone.Negative,
                )
            }

            isFullAudit &&
                scan.strategyProbeReport?.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_audit_short_circuit_title),
                    body = stringResource(R.string.diagnostics_profile_audit_short_circuit_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            isFullAudit && scan.strategyProbeReport?.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
                val assessment = scan.strategyProbeReport.auditAssessment
                val coverage = assessment?.coverage
                val executed = (coverage?.tcpCandidatesExecuted ?: 0) + (coverage?.quicCandidatesExecuted ?: 0)
                val planned = (coverage?.tcpCandidatesPlanned ?: 0) + (coverage?.quicCandidatesPlanned ?: 0)
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_audit_partial_results_title),
                    body = stringResource(R.string.diagnostics_profile_audit_partial_results_body, executed, planned),
                    tone = DiagnosticsTone.Warning,
                )
            }

            isFullAudit && scan.strategyProbeReport != null -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_audit_ready_title),
                    body = stringResource(R.string.diagnostics_profile_audit_ready_body),
                    tone = DiagnosticsTone.Positive,
                )
            }

            strategyProbeSelected &&
                scan.strategyProbeReport?.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_probe_short_circuit_title),
                    body = stringResource(R.string.diagnostics_profile_probe_short_circuit_body),
                    tone = DiagnosticsTone.Warning,
                )
            }

            strategyProbeSelected &&
                scan.strategyProbeReport?.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
                val assessment = scan.strategyProbeReport.auditAssessment
                val coverage = assessment?.coverage
                val executed = (coverage?.tcpCandidatesExecuted ?: 0) + (coverage?.quicCandidatesExecuted ?: 0)
                val planned = (coverage?.tcpCandidatesPlanned ?: 0) + (coverage?.quicCandidatesPlanned ?: 0)
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_probe_partial_results_title),
                    body = stringResource(R.string.diagnostics_profile_probe_partial_results_body, executed, planned),
                    tone = DiagnosticsTone.Warning,
                )
            }

            strategyProbeSelected && scan.strategyProbeReport != null -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_probe_ready_title),
                    body = stringResource(R.string.diagnostics_profile_probe_ready_body),
                    tone = DiagnosticsTone.Positive,
                )
            }

            isFullAudit -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_audit_profile_title),
                    body = stringResource(R.string.diagnostics_profile_audit_body),
                    tone = DiagnosticsTone.Neutral,
                )
            }

            strategyProbeSelected -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_probe_profile_title),
                    body = stringResource(R.string.diagnostics_profile_probe_body),
                    tone = DiagnosticsTone.Neutral,
                )
            }

            else -> {
                WorkflowStatusUiModel(
                    title = stringResource(R.string.diagnostics_profile_connectivity_title),
                    body = stringResource(R.string.diagnostics_profile_connectivity_body),
                    tone = DiagnosticsTone.Neutral,
                )
            }
        }
    return DiagnosticsScanWorkflowPresentationUiModel(
        title = status.title,
        body = status.body,
        tone = status.tone,
        badges = workflowBadges(profile, strategyProbeSelected, isFullAudit).toImmutableList(),
        rawActionLabel =
            when {
                !scan.runRawEnabled && isFullAudit -> stringResource(R.string.diagnostics_action_audit_unavailable)
                !scan.runRawEnabled -> stringResource(R.string.diagnostics_action_probe_unavailable)
                isFullAudit -> stringResource(R.string.diagnostics_action_start_audit)
                strategyProbeSelected -> stringResource(R.string.diagnostics_action_start_probe)
                else -> stringResource(R.string.diagnostics_action_raw)
            },
        inPathActionLabel = stringResource(R.string.diagnostics_action_in_path),
    )
}

@Composable
private fun workflowBadges(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): List<DiagnosticsScanWorkflowBadgeUiModel> =
    buildList {
        if (isFullAudit) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_http_https_quic),
                    DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_all_builtin),
                    DiagnosticsTone.Warning,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_raw_only),
                    DiagnosticsTone.Warning,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_manual_apply),
                    DiagnosticsTone.Positive,
                ),
            )
        } else if (strategyProbeSelected) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_http_https_quic),
                    DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_raw_only),
                    DiagnosticsTone.Warning,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_manual_apply),
                    DiagnosticsTone.Positive,
                ),
            )
        } else {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_dns_http_https_tcp),
                    DiagnosticsTone.Info,
                ),
            )
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_raw_and_in_path),
                    DiagnosticsTone.Positive,
                ),
            )
        }
        if (profile.manualOnly) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_manual_only),
                    DiagnosticsTone.Warning,
                ),
            )
        }
        if (profile.regionTag?.equals("ru", ignoreCase = true) == true) {
            add(
                DiagnosticsScanWorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_region_net),
                    DiagnosticsTone.Warning,
                ),
            )
        }
    }

@Composable
private fun WorkflowActionRow(
    strategyProbeSelected: Boolean,
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    spacing: androidx.compose.ui.unit.Dp,
    presentation: DiagnosticsScanWorkflowPresentationUiModel,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
) {
    if (strategyProbeSelected) {
        RipDpiButton(
            text = presentation.rawActionLabel,
            onClick = onRunRawScan,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunRawAction),
            enabled = scan.runRawEnabled,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        RipDpiButton(
            text = presentation.rawActionLabel,
            onClick = onRunRawScan,
            modifier =
                Modifier
                    .weight(1f)
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunRawAction),
            enabled = scan.runRawEnabled,
        )
        RipDpiButton(
            text = presentation.inPathActionLabel,
            onClick = onRunInPathScan,
            modifier =
                Modifier
                    .weight(1f)
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunInPathAction),
            variant = RipDpiButtonVariant.Outline,
            enabled = scan.runInPathEnabled,
        )
    }
}
