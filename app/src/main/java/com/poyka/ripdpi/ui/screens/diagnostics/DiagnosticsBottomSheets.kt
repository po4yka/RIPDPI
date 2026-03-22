package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsContextGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsDiagnosisUiModel
import com.poyka.ripdpi.activities.DiagnosticsEventUiModel
import com.poyka.ripdpi.activities.DiagnosticsNetworkSnapshotUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeGroupUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsSessionRowUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiBottomSheet
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsBottomSheetHost(
    uiState: DiagnosticsUiState,
    onDismissSessionDetail: () -> Unit,
    onToggleSensitiveSessionDetails: () -> Unit,
    onSelectStrategyProbeCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
    onDismissStrategyProbeCandidate: () -> Unit,
    onSelectEvent: (String) -> Unit,
    onDismissEventDetail: () -> Unit,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
    onDismissProbeDetail: () -> Unit,
    onDismissApproachDetail: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors

    if (uiState.selectedStrategyProbeCandidate == null) {
        uiState.selectedSessionDetail?.let { detail ->
            RipDpiBottomSheet(
                onDismissRequest = onDismissSessionDetail,
                title = detail.session.title,
                message = detail.session.subtitle,
                icon = RipDpiIcons.Info,
                testTag = RipDpiTestTags.DiagnosticsSessionDetailSheet,
            ) {
                StatusIndicator(
                    label = detail.session.status,
                    tone = statusTone(detail.session.tone),
                )
                if (detail.hasSensitiveDetails) {
                    RipDpiButton(
                        text =
                            if (detail.sensitiveDetailsVisible) {
                                stringResource(R.string.diagnostics_sensitive_hide)
                            } else {
                                stringResource(R.string.diagnostics_sensitive_show)
                            },
                        onClick = onToggleSensitiveSessionDetails,
                        variant = RipDpiButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (detail.diagnoses.isNotEmpty()) {
                    DiagnosisSummaryCard(
                        diagnoses = detail.diagnoses,
                        reportMetadata = detail.reportMetadata,
                    )
                }
                detail.strategyProbeReport?.let { report ->
                    StrategyProbeReportCard(
                        report = report,
                        onSelectCandidate = onSelectStrategyProbeCandidate,
                    )
                }
                detail.contextGroups.forEach { group ->
                    ContextGroupCard(group = group)
                }
                detail.probeGroups.forEach { group ->
                    Text(
                        text = group.title,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    group.items.forEach { probe ->
                        ProbeResultRow(
                            probe = probe,
                            onClick = { onSelectProbe(probe) },
                        )
                    }
                }
                detail.snapshots.forEach { snapshot ->
                    SnapshotCard(snapshot = snapshot)
                }
                if (detail.events.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.diagnostics_events_title),
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    detail.events.take(6).forEach { event ->
                        EventRow(event = event, onClick = { onSelectEvent(event.id) })
                    }
                }
            }
        }
    }
    uiState.selectedEvent?.let { event ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissEventDetail,
            title = event.source,
            message = event.createdAtLabel,
            icon = RipDpiIcons.Info,
        ) {
            StatusIndicator(label = event.severity, tone = statusTone(event.tone))
            Text(
                text = event.message,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
        }
    }

    uiState.selectedApproachDetail?.let { detail ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissApproachDetail,
            title = detail.approach.title,
            message = detail.approach.subtitle,
            icon = RipDpiIcons.Search,
        ) {
            StatusIndicator(label = detail.approach.verificationState, tone = statusTone(detail.approach.tone))
            if (detail.signature.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_approaches_signature_title),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                detail.signature.forEach { item ->
                    SettingsRow(title = item.label, value = item.value, monospaceValue = false)
                }
            }
            if (detail.breakdown.isNotEmpty()) {
                MetricsRow(metrics = detail.breakdown)
            }
            if (detail.runtimeSummary.isNotEmpty()) {
                MetricsRow(metrics = detail.runtimeSummary)
            }
            detail.recentSessions.forEach { session ->
                SessionRow(session = session, onClick = {})
            }
            detail.recentUsageNotes.forEach { note ->
                Text(
                    text = note,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            detail.failureNotes.forEach { note ->
                Text(
                    text = note,
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.foreground,
                )
            }
        }
    }

    uiState.selectedProbe?.let { probe ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissProbeDetail,
            title = probe.target,
            message = probe.probeType,
            icon = RipDpiIcons.Search,
            testTag = RipDpiTestTags.DiagnosticsProbeDetailSheet,
        ) {
            StatusIndicator(label = probe.outcome, tone = statusTone(probe.tone))
            probe.details.forEach { detail ->
                SettingsRow(
                    title = detail.label,
                    value = detail.value,
                    monospaceValue = true,
                )
            }
        }
    }

    uiState.selectedStrategyProbeCandidate?.let { candidate ->
        RipDpiBottomSheet(
            onDismissRequest = onDismissStrategyProbeCandidate,
            title = candidate.label,
            message = "${candidate.familyLabel} · ${candidate.suiteLabel}",
            icon = RipDpiIcons.Search,
            testTag = RipDpiTestTags.DiagnosticsStrategyCandidateDetailSheet,
        ) {
            StatusIndicator(
                label =
                    if (candidate.recommended) {
                        stringResource(R.string.diagnostics_probe_status_recommended)
                    } else {
                        candidate.outcome
                    },
                tone =
                    if (candidate.recommended) {
                        StatusIndicatorTone.Active
                    } else {
                        statusTone(candidate.tone)
                    },
            )
            Text(
                text = candidate.rationale,
                style = RipDpiThemeTokens.type.body,
                color = colors.foreground,
            )
            MetricsRow(metrics = candidate.metrics)
            if (candidate.notes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_audit_candidate_notes_title),
                    modifier =
                        Modifier.ripDpiTestTag(
                            RipDpiTestTags.DiagnosticsStrategyCandidateNotesSection,
                        ),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                candidate.notes.forEach { note ->
                    Text(
                        text = note,
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                }
            }
            if (candidate.signature.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_audit_candidate_signature_title),
                    modifier =
                        Modifier.ripDpiTestTag(
                            RipDpiTestTags.DiagnosticsStrategyCandidateSignatureSection,
                        ),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                candidate.signature.forEach { field ->
                    SettingsRow(
                        title = field.label,
                        value = field.value,
                        monospaceValue = false,
                    )
                }
            }
            if (candidate.resultGroups.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_audit_candidate_results_title),
                    modifier =
                        Modifier.ripDpiTestTag(
                            RipDpiTestTags.DiagnosticsStrategyCandidateResultsSection,
                        ),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                candidate.resultGroups.forEach { group ->
                    Text(
                        text = group.title,
                        style = RipDpiThemeTokens.type.secondaryBody,
                        color = colors.mutedForeground,
                    )
                    group.items.forEach { probe ->
                        ProbeResultRow(probe = probe, onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosisSummaryCard(
    diagnoses: List<DiagnosticsDiagnosisUiModel>,
    reportMetadata: List<com.poyka.ripdpi.activities.DiagnosticsFieldUiModel>,
) {
    val colors = RipDpiThemeTokens.colors
    RipDpiCard {
        Text(
            text = stringResource(R.string.diagnostics_results_section),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        diagnoses.forEach { diagnosis ->
            StatusIndicator(label = diagnosis.code, tone = statusTone(diagnosis.tone))
            Text(
                text = diagnosis.summary,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.foreground,
            )
        }
        reportMetadata.forEach { field ->
            SettingsRow(title = field.label, value = field.value, monospaceValue = false)
        }
    }
}
