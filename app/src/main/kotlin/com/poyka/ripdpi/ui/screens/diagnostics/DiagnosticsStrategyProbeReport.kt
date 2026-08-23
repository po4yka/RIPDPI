package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportPresentationUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeWinningPathUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeCompletionKind
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.ripDpiClickable
import com.poyka.ripdpi.ui.debug.TrackRecomposition
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun StrategyProbeReportCard(
    report: DiagnosticsStrategyProbeReportUiModel,
    onSelectCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
) {
    TrackRecomposition("StrategyProbeReportCard")
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val presentation = report.presentation ?: reportPresentationFallback(report)
    var showFullMatrix by rememberSaveable(
        report.suiteId,
        report.completionKind,
        report.recommendation?.headline,
        report.recommendation?.rationale,
        report.winningPath?.tcpWinner?.id,
        report.winningPath?.quicWinner?.id,
    ) { mutableStateOf(presentation.showFullMatrixInitially) }
    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyProbeReport),
    ) {
        StrategyReportHeader(report = report, presentation = presentation)
        StrategyReportWinningPath(
            report = report,
            presentation = presentation,
            showFullMatrix = showFullMatrix,
            onToggleFullMatrix = { showFullMatrix = !showFullMatrix },
            onSelectCandidate = onSelectCandidate,
        )
        StrategyReportAuditAssessment(report = report, presentation = presentation)
        StrategyReportBadges(suiteLabel = report.suiteLabel, manualApplyBadge = presentation.manualApplyBadge)
        StrategyReportSummary(
            report = report,
            presentation = presentation,
            showFullMatrix = showFullMatrix,
        )
        StrategyReportRecommendation(report = report)
        StrategyReportFamilyMatrix(
            report = report,
            showFullMatrix = showFullMatrix,
            onSelectCandidate = onSelectCandidate,
        )
    }
}

@Composable
private fun StrategyReportHeader(
    report: DiagnosticsStrategyProbeReportUiModel,
    presentation: DiagnosticsStrategyProbeReportPresentationUiModel,
) {
    val colors = RipDpiThemeTokens.colors
    StatusIndicator(
        label = presentation.statusLabel,
        tone = statusTone(presentation.statusTone),
    )
    Text(
        text = presentation.matrixTitle.uppercase(),
        style = RipDpiThemeTokens.type.sectionTitle,
        color = colors.foreground,
    )
    report.recommendation?.let { recommendation ->
        Text(
            text = recommendation.headline,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = recommendation.rationale,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun StrategyReportWinningPath(
    report: DiagnosticsStrategyProbeReportUiModel,
    presentation: DiagnosticsStrategyProbeReportPresentationUiModel,
    showFullMatrix: Boolean,
    onToggleFullMatrix: () -> Unit,
    onSelectCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
) {
    val winningPath = report.winningPath?.takeIf { presentation.supportsWinningPath } ?: return
    HorizontalDivider()
    WinningPathSection(
        winningPath = winningPath,
        onSelectTcpWinner =
            report.candidateDetails[winningPath.tcpWinner.id]?.let { detail ->
                { onSelectCandidate(detail) }
            },
        onSelectQuicWinner =
            report.candidateDetails[winningPath.quicWinner.id]?.let { detail ->
                { onSelectCandidate(detail) }
            },
    )
    RipDpiButton(
        text =
            if (showFullMatrix) {
                stringResource(R.string.diagnostics_audit_hide_full_matrix)
            } else {
                stringResource(R.string.diagnostics_audit_show_full_matrix)
            },
        onClick = onToggleFullMatrix,
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyFullMatrixToggle),
        variant = RipDpiButtonVariant.Outline,
    )
}

@Composable
private fun StrategyReportAuditAssessment(
    report: DiagnosticsStrategyProbeReportUiModel,
    presentation: DiagnosticsStrategyProbeReportPresentationUiModel,
) {
    val assessment = report.auditAssessment ?: return
    val colors = RipDpiThemeTokens.colors
    HorizontalDivider()
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyAuditAssessment),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.diagnostics_audit_confidence_title),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            StatusIndicator(
                label = presentation.auditConfidenceLabel.orEmpty(),
                tone = statusTone(presentation.auditConfidenceTone ?: DiagnosticsTone.Neutral),
            )
        }
        Text(
            text = assessment.confidence.rationale,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        MetricsRow(metrics = presentation.auditAssessmentMetrics)
        StrategyReportConfidenceWarnings(assessment = assessment)
    }
}

@Composable
private fun StrategyReportConfidenceWarnings(assessment: StrategyProbeAuditAssessment) {
    val colors = RipDpiThemeTokens.colors
    if (assessment.confidence.level == StrategyProbeAuditConfidenceLevel.MEDIUM) {
        Text(
            text = stringResource(R.string.diagnostics_audit_medium_confidence_note),
            modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyAuditMediumConfidenceNote),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.warning,
        )
    }
    assessment.confidence.warnings.forEach { warning ->
        Text(
            text = "- $warning",
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun StrategyReportBadges(
    suiteLabel: String,
    manualApplyBadge: String,
) {
    val spacing = RipDpiThemeTokens.spacing
    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        items(
            listOf(
                suiteLabel to DiagnosticsTone.Info,
                manualApplyBadge to DiagnosticsTone.Positive,
            ),
            key = { it.first },
            contentType = { "strategy_report_badge" },
        ) { badge ->
            EventBadge(text = badge.first, tone = badge.second)
        }
    }
}

@Composable
private fun StrategyReportSummary(
    report: DiagnosticsStrategyProbeReportUiModel,
    presentation: DiagnosticsStrategyProbeReportPresentationUiModel,
    showFullMatrix: Boolean,
) {
    if (report.summaryMetrics.isEmpty()) return
    HorizontalDivider()
    Text(
        text = stringResource(R.string.diagnostics_audit_summary_title),
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyProbeSummary),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
    MetricsRow(metrics = report.summaryMetrics)
    if (!presentation.supportsWinningPath || showFullMatrix) {
        Text(
            text = stringResource(R.string.diagnostics_audit_candidate_open_hint),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
    if (report.auditAssessment?.confidence?.level == StrategyProbeAuditConfidenceLevel.LOW) {
        WarningBanner(
            title = stringResource(R.string.diagnostics_audit_low_confidence_title),
            message = stringResource(R.string.diagnostics_audit_low_confidence_body),
            testTag = RipDpiTestTags.DiagnosticsStrategyAuditLowConfidenceBanner,
            tone = WarningBannerTone.Warning,
        )
    }
}

@Composable
private fun StrategyReportRecommendation(report: DiagnosticsStrategyProbeReportUiModel) {
    val recommendation = report.recommendation ?: return
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        Text(
            text = stringResource(R.string.diagnostics_probe_manual_apply_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.diagnostics_probe_manual_apply_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
    recommendation.fields.forEach { field ->
        SettingsRow(title = field.label, value = field.value)
    }
    StrategyReportSignature(report = report)
}

@Composable
private fun StrategyReportSignature(report: DiagnosticsStrategyProbeReportUiModel) {
    val recommendation = report.recommendation ?: return
    if (recommendation.signature.isEmpty()) return
    HorizontalDivider()
    Text(
        text = stringResource(R.string.diagnostics_probe_signature_title),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
    recommendation.signature.forEach { field ->
        SettingsRow(title = field.label, value = field.value)
    }
}

@Composable
private fun StrategyReportFamilyMatrix(
    report: DiagnosticsStrategyProbeReportUiModel,
    showFullMatrix: Boolean,
    onSelectCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
) {
    if (!showFullMatrix) return
    val spacing = RipDpiThemeTokens.spacing
    report.families.forEach { family ->
        HorizontalDivider()
        Text(
            text = family.title,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            family.candidates.forEach { candidate ->
                StrategyProbeCandidateRow(
                    candidate = candidate,
                    onClick =
                        report.candidateDetails[candidate.id]?.let { detail ->
                            { onSelectCandidate(detail) }
                        },
                )
            }
        }
    }
}

@Composable
private fun reportPresentationFallback(
    report: DiagnosticsStrategyProbeReportUiModel,
): DiagnosticsStrategyProbeReportPresentationUiModel {
    val isFullAudit = report.suiteId == StrategyProbeSuiteFullMatrixV1
    val isDnsShortCircuited = report.completionKind == StrategyProbeCompletionKind.DNS_SHORT_CIRCUITED
    val isPartialResults = report.completionKind == StrategyProbeCompletionKind.PARTIAL_RESULTS
    val isIncomplete = isDnsShortCircuited || isPartialResults
    val supportsWinningPath = isFullAudit && !isIncomplete && report.winningPath != null
    val coverage = report.auditAssessment?.coverage
    val executed = (coverage?.tcpCandidatesExecuted ?: 0) + (coverage?.quicCandidatesExecuted ?: 0)
    val planned = (coverage?.tcpCandidatesPlanned ?: 0) + (coverage?.quicCandidatesPlanned ?: 0)
    val confidenceLabel =
        report.auditAssessment
            ?.confidence
            ?.level
            ?.let { auditConfidenceLabel(it) }
    val confidenceTone =
        report.auditAssessment
            ?.confidence
            ?.level
            ?.let(::auditConfidenceTone)
    return DiagnosticsStrategyProbeReportPresentationUiModel(
        statusLabel =
            reportFallbackStatusLabel(
                isDnsShortCircuited = isDnsShortCircuited,
                isPartialResults = isPartialResults,
                isFullAudit = isFullAudit,
            ),
        statusTone = if (isIncomplete) DiagnosticsTone.Warning else DiagnosticsTone.Positive,
        matrixTitle =
            reportFallbackMatrixTitle(
                isDnsShortCircuited = isDnsShortCircuited,
                isPartialResults = isPartialResults,
                isFullAudit = isFullAudit,
                executed = executed,
                planned = planned,
            ),
        manualApplyBadge = stringResource(R.string.diagnostics_profile_badge_manual_apply),
        supportsWinningPath = supportsWinningPath,
        isIncomplete = isIncomplete,
        showFullMatrixInitially = !supportsWinningPath,
        auditConfidenceLabel = confidenceLabel,
        auditConfidenceTone = confidenceTone,
        auditAssessmentMetrics =
            report.auditAssessment
                ?.let { auditAssessmentMetrics(it) }
                ?: kotlinx.collections.immutable.persistentListOf(),
    )
}

@Composable
private fun reportFallbackStatusLabel(
    isDnsShortCircuited: Boolean,
    isPartialResults: Boolean,
    isFullAudit: Boolean,
): String =
    when {
        isDnsShortCircuited && isFullAudit -> stringResource(R.string.diagnostics_audit_short_circuit_title)
        isDnsShortCircuited -> stringResource(R.string.diagnostics_probe_short_circuit_title)
        isPartialResults && isFullAudit -> stringResource(R.string.diagnostics_audit_partial_results_title)
        isPartialResults -> stringResource(R.string.diagnostics_probe_partial_results_title)
        isFullAudit -> stringResource(R.string.diagnostics_audit_ready_title)
        else -> stringResource(R.string.diagnostics_probe_ready_title)
    }

@Composable
private fun reportFallbackMatrixTitle(
    isDnsShortCircuited: Boolean,
    isPartialResults: Boolean,
    isFullAudit: Boolean,
    executed: Int,
    planned: Int,
): String =
    when {
        isDnsShortCircuited && isFullAudit -> stringResource(R.string.diagnostics_audit_short_circuit_matrix_title)
        isDnsShortCircuited -> stringResource(R.string.diagnostics_probe_short_circuit_recommendation_title)
        isPartialResults -> stringResource(R.string.diagnostics_partial_results_matrix_title, executed, planned)
        isFullAudit -> stringResource(R.string.diagnostics_audit_matrix_title)
        else -> stringResource(R.string.diagnostics_probe_recommendation_title)
    }

@Composable
private fun WinningPathSection(
    winningPath: DiagnosticsStrategyProbeWinningPathUiModel,
    onSelectTcpWinner: (() -> Unit)?,
    onSelectQuicWinner: (() -> Unit)?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(
        variant = RipDpiCardVariant.Tonal,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyWinningPath),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_audit_winning_path_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        winningPath.dnsLaneLabel?.let { dnsLaneLabel ->
            SettingsRow(
                title = stringResource(R.string.diagnostics_audit_winning_dns_lane),
                value = dnsLaneLabel,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            WinningPathCandidateCard(
                title = stringResource(R.string.diagnostics_audit_winning_tcp_title),
                candidate = winningPath.tcpWinner,
                onClick = onSelectTcpWinner,
                testTag = RipDpiTestTags.DiagnosticsStrategyWinningTcpAction,
            )
            WinningPathCandidateCard(
                title = stringResource(R.string.diagnostics_audit_winning_quic_title),
                candidate = winningPath.quicWinner,
                onClick = onSelectQuicWinner,
                testTag = RipDpiTestTags.DiagnosticsStrategyWinningQuicAction,
            )
        }
        Text(
            text = stringResource(R.string.diagnostics_audit_winning_path_hint),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun WinningPathCandidateCard(
    title: String,
    candidate: DiagnosticsStrategyProbeWinningCandidateUiModel,
    testTag: String,
    onClick: (() -> Unit)?,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(testTag)
                .let { modifier ->
                    if (onClick != null) {
                        modifier.ripDpiClickable(role = Role.Button, onClick = onClick)
                    } else {
                        modifier
                    }
                },
        shape = RipDpiThemeTokens.shapes.lg,
        color = colors.inputBackground,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(RipDpiThemeTokens.layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                )
                StatusIndicator(
                    label = candidate.outcome,
                    tone = statusTone(candidate.tone),
                )
            }
            Text(
                text = candidate.label,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            Text(
                text = candidate.familyLabel,
                style = RipDpiThemeTokens.type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text = candidate.rationale,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
            MetricsRow(metrics = candidate.metrics)
            if (candidate.hiddenCandidateCount > 0) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.diagnostics_audit_hidden_candidates_count,
                            candidate.hiddenCandidateCount,
                            candidate.hiddenCandidateCount,
                        ),
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun auditConfidenceLabel(level: StrategyProbeAuditConfidenceLevel): String =
    stringResource(
        when (level) {
            StrategyProbeAuditConfidenceLevel.HIGH -> R.string.diagnostics_audit_confidence_high
            StrategyProbeAuditConfidenceLevel.MEDIUM -> R.string.diagnostics_audit_confidence_medium
            StrategyProbeAuditConfidenceLevel.LOW -> R.string.diagnostics_audit_confidence_low
        },
    )

private fun auditConfidenceTone(level: StrategyProbeAuditConfidenceLevel): DiagnosticsTone =
    when (level) {
        StrategyProbeAuditConfidenceLevel.HIGH -> DiagnosticsTone.Positive
        StrategyProbeAuditConfidenceLevel.MEDIUM -> DiagnosticsTone.Warning
        StrategyProbeAuditConfidenceLevel.LOW -> DiagnosticsTone.Negative
    }

@Composable
private fun auditAssessmentMetrics(
    assessment: StrategyProbeAuditAssessment,
): kotlinx.collections.immutable.ImmutableList<com.poyka.ripdpi.activities.DiagnosticsMetricUiModel> =
    kotlinx.collections.immutable.persistentListOf(
        com.poyka.ripdpi.activities.DiagnosticsMetricUiModel(
            label = stringResource(R.string.home_diagnostics_confidence_label),
            value =
                stringResource(
                    R.string.diagnostics_audit_confidence_value,
                    auditConfidenceLabel(assessment.confidence.level),
                    assessment.confidence.score,
                ),
            tone = auditConfidenceTone(assessment.confidence.level),
        ),
        com.poyka.ripdpi.activities.DiagnosticsMetricUiModel(
            label = stringResource(R.string.diagnostics_audit_matrix_coverage_label),
            value = stringResource(R.string.percent_value, assessment.coverage.matrixCoveragePercent),
            tone =
                if (assessment.coverage.matrixCoveragePercent >= 75) {
                    DiagnosticsTone.Positive
                } else {
                    DiagnosticsTone.Warning
                },
        ),
        com.poyka.ripdpi.activities.DiagnosticsMetricUiModel(
            label = stringResource(R.string.diagnostics_audit_winner_coverage_label),
            value = stringResource(R.string.percent_value, assessment.coverage.winnerCoveragePercent),
            tone =
                if (assessment.coverage.winnerCoveragePercent >= 50) {
                    DiagnosticsTone.Positive
                } else {
                    DiagnosticsTone.Warning
                },
        ),
    )

@Composable
private fun StrategyProbeCandidateRow(
    candidate: DiagnosticsStrategyProbeCandidateUiModel,
    onClick: (() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .ripDpiTestTag(RipDpiTestTags.diagnosticsStrategyCandidate(candidate.id))
                .let { modifier ->
                    if (onClick != null) {
                        modifier.ripDpiClickable(role = Role.Button, onClick = onClick)
                    } else {
                        modifier
                    }
                },
        shape = RipDpiThemeTokens.shapes.lg,
        color = colors.inputBackground,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(RipDpiThemeTokens.layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.label,
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = colors.foreground,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusIndicator(
                    label =
                        when {
                            candidate.recommended && candidate.skipped -> {
                                stringResource(R.string.diagnostics_probe_status_fallback)
                            }

                            candidate.recommended -> {
                                stringResource(R.string.diagnostics_probe_status_recommended)
                            }

                            candidate.skipped -> {
                                stringResource(R.string.diagnostics_probe_status_skipped)
                            }

                            else -> {
                                candidate.outcome
                            }
                        },
                    tone =
                        if (candidate.recommended && !candidate.skipped) {
                            StatusIndicatorTone.Active
                        } else {
                            statusTone(candidate.tone)
                        },
                )
            }
            Text(
                text = candidate.rationale,
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
            MetricsRow(metrics = candidate.metrics)
            if (onClick != null) {
                Text(
                    text = stringResource(R.string.diagnostics_audit_candidate_open_hint),
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = colors.mutedForeground,
                )
            }
        }
    }
}
