package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsRemediationActionKindUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanWorkflowBadgeUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanWorkflowPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.DiagnosticsRemediationLadderCard
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

private const val DiagnosticsBadgeWrapFontScale = 1.5f

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
        WorkflowBadgeRow(presentation.badges)
        scan.selectedProfileScopeLabel?.let { label ->
            Text(
                text = label,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
        }
        WorkflowRemediationLadder(
            scan = scan,
            onOpenAdvancedSettings = onOpenAdvancedSettings,
            onOpenDnsSettings = onOpenDnsSettings,
            onRequestVpnPermission = onRequestVpnPermission,
            onOpenHistory = onOpenHistory,
            onOpenModeEditor = onOpenModeEditor,
            onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
        )
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

@Composable
private fun WorkflowRemediationLadder(
    scan: DiagnosticsScanUiModel,
    onOpenAdvancedSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
) {
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
                    }
                }
            },
            cardTestTag = RipDpiTestTags.DiagnosticsRemediationLadderCard,
            actionTestTag = RipDpiTestTags.DiagnosticsRemediationLadderAction,
        )
    }
}

@Composable
private fun WorkflowBadgeRow(badges: List<DiagnosticsScanWorkflowBadgeUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    if (LocalDensity.current.fontScale >= DiagnosticsBadgeWrapFontScale) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            badges.forEach { badge ->
                EventBadge(text = badge.text, tone = badge.tone)
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(
                items = badges,
                key = { it.text },
                contentType = { "workflow_badge" },
            ) { badge ->
                EventBadge(text = badge.text, tone = badge.tone)
            }
        }
    }
}

private data class WorkflowStatusUiModel(
    val title: String,
    val body: String,
    val tone: DiagnosticsTone,
)

@Composable
private fun workflowPresentationFallback(
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): DiagnosticsScanWorkflowPresentationUiModel {
    val status = workflowStatus(scan, strategyProbeSelected, isFullAudit)
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
private fun workflowStatus(
    scan: DiagnosticsScanUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): WorkflowStatusUiModel =
    when {
        scan.isBusy -> {
            busyWorkflowStatus(strategyProbeSelected, isFullAudit)
        }

        isFullAudit && !scan.runRawEnabled -> {
            unavailableWorkflowStatus(scan, audit = true)
        }

        strategyProbeSelected && !scan.runRawEnabled -> {
            unavailableWorkflowStatus(scan, audit = false)
        }

        isFullAudit -> {
            auditWorkflowStatus(scan)
        }

        strategyProbeSelected -> {
            probeWorkflowStatus(scan)
        }

        else -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_profile_connectivity_title),
                body = stringResource(R.string.diagnostics_profile_connectivity_body),
                tone = DiagnosticsTone.Neutral,
            )
        }
    }

@Composable
private fun busyWorkflowStatus(
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): WorkflowStatusUiModel =
    when {
        isFullAudit -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_progress_title),
                body = stringResource(R.string.diagnostics_profile_audit_running_body),
                tone = DiagnosticsTone.Warning,
            )
        }

        strategyProbeSelected -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_progress_title),
                body = stringResource(R.string.diagnostics_profile_probe_running_body),
                tone = DiagnosticsTone.Warning,
            )
        }

        else -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_status_running),
                body = stringResource(R.string.diagnostics_profile_connectivity_running_body),
                tone = DiagnosticsTone.Warning,
            )
        }
    }

@Composable
private fun unavailableWorkflowStatus(
    scan: DiagnosticsScanUiModel,
    audit: Boolean,
): WorkflowStatusUiModel =
    WorkflowStatusUiModel(
        title =
            scan.workflowRestriction?.title
                ?: stringResource(
                    if (audit) {
                        R.string.diagnostics_audit_unavailable_title
                    } else {
                        R.string.diagnostics_probe_unavailable_title
                    },
                ),
        body =
            scan.workflowRestriction?.body
                ?: stringResource(
                    if (audit) {
                        R.string.diagnostics_profile_audit_unavailable_body
                    } else {
                        R.string.diagnostics_profile_probe_unavailable_body
                    },
                ),
        tone = DiagnosticsTone.Negative,
    )

@Composable
private fun auditWorkflowStatus(scan: DiagnosticsScanUiModel): WorkflowStatusUiModel =
    when (scan.strategyProbeReport?.completionKind) {
        StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_short_circuit_title),
                body = stringResource(R.string.diagnostics_profile_audit_short_circuit_body),
                tone = DiagnosticsTone.Warning,
            )
        }

        StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
            partialWorkflowStatus(scan, audit = true)
        }

        null -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_profile_title),
                body = stringResource(R.string.diagnostics_profile_audit_body),
                tone = DiagnosticsTone.Neutral,
            )
        }

        else -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_ready_title),
                body = stringResource(R.string.diagnostics_profile_audit_ready_body),
                tone = DiagnosticsTone.Positive,
            )
        }
    }

@Composable
private fun probeWorkflowStatus(scan: DiagnosticsScanUiModel): WorkflowStatusUiModel =
    when (scan.strategyProbeReport?.completionKind) {
        StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_short_circuit_title),
                body = stringResource(R.string.diagnostics_profile_probe_short_circuit_body),
                tone = DiagnosticsTone.Warning,
            )
        }

        StrategyProbeCompletionKind.PARTIAL_RESULTS -> {
            partialWorkflowStatus(scan, audit = false)
        }

        null -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_profile_title),
                body = stringResource(R.string.diagnostics_profile_probe_body),
                tone = DiagnosticsTone.Neutral,
            )
        }

        else -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_ready_title),
                body = stringResource(R.string.diagnostics_profile_probe_ready_body),
                tone = DiagnosticsTone.Positive,
            )
        }
    }

@Composable
private fun partialWorkflowStatus(
    scan: DiagnosticsScanUiModel,
    audit: Boolean,
): WorkflowStatusUiModel {
    val coverage = scan.strategyProbeReport?.auditAssessment?.coverage
    val executed = (coverage?.tcpCandidatesExecuted ?: 0) + (coverage?.quicCandidatesExecuted ?: 0)
    val planned = (coverage?.tcpCandidatesPlanned ?: 0) + (coverage?.quicCandidatesPlanned ?: 0)
    return WorkflowStatusUiModel(
        title =
            stringResource(
                if (audit) {
                    R.string.diagnostics_audit_partial_results_title
                } else {
                    R.string.diagnostics_probe_partial_results_title
                },
            ),
        body =
            stringResource(
                if (audit) {
                    R.string.diagnostics_profile_audit_partial_results_body
                } else {
                    R.string.diagnostics_profile_probe_partial_results_body
                },
                executed,
                planned,
            ),
        tone = DiagnosticsTone.Warning,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        RipDpiButton(
            text = presentation.rawActionLabel,
            onClick = onRunRawScan,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunRawAction),
            enabled = scan.runRawEnabled,
        )
        RipDpiButton(
            text = presentation.inPathActionLabel,
            onClick = onRunInPathScan,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunInPathAction),
            variant = RipDpiButtonVariant.Outline,
            enabled = scan.runInPathEnabled,
        )
    }
}
