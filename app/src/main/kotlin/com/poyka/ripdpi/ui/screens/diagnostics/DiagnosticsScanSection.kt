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
import androidx.compose.runtime.ReadOnlyComposable
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
import com.poyka.ripdpi.activities.DiagnosticsDiagnosisUiModel
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

private const val MaxVisibleEvidence = 3
private const val LiveProbePreviewCount = 8

@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
internal fun ScanSection(
    scan: DiagnosticsScanUiModel,
    onSelectProfile: (String) -> Unit,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onRequestVpnPermission: () -> Unit,
    onSelectStrategyProbeCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenOwnedStackBrowser: (String) -> Unit,
) {
    TrackRecomposition("ScanSection")
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val selectedProfile = scan.selectedProfile
    val strategyProbeSelected = selectedProfile?.kind == com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE
    val scanStateTag =
        when {
            scan.activeProgress != null -> RipDpiTestTags.DiagnosticsScanStateProgress

            scan.strategyProbeReport != null ||
                scan.latestResults.isNotEmpty() ||
                scan.latestSession != null ||
                scan.resolverRecommendation != null
            -> RipDpiTestTags.DiagnosticsScanStateContent

            else -> RipDpiTestTags.DiagnosticsScanStateIdle
        }
    var showProfilePicker by rememberSaveable { mutableStateOf(false) }
    if (showProfilePicker) {
        ProfileSelectionBottomSheet(
            profiles = scan.profiles,
            selectedProfileId = scan.selectedProfileId,
            onSelectProfile = onSelectProfile,
            onDismiss = { showProfilePicker = false },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (scan.diagnoses.isNotEmpty()) {
            item {
                DiagnosisSummaryCard(
                    title = stringResource(R.string.diagnostics_diagnosis_summary_title),
                    diagnoses = scan.diagnoses,
                )
            }
        }
        item {
            CompactProfileRow(
                profile = scan.selectedProfile,
                onChangeProfile = { showProfilePicker = true },
            )
        }
        selectedProfile?.takeIf { it.regionTag?.equals("ru", ignoreCase = true) == true }?.let {
            item {
                WarningBanner(
                    title = stringResource(R.string.diagnostics_region_suite_title),
                    message = stringResource(R.string.diagnostics_region_suite_message),
                    tone = WarningBannerTone.Restricted,
                )
            }
        }
        scan.policyNoticeMessage?.let { message ->
            item {
                WarningBanner(
                    title = stringResource(R.string.diagnostics_region_suite_title),
                    message = message,
                    tone = WarningBannerTone.Restricted,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsScanPolicyNotice),
                )
            }
        }
        selectedProfile?.let { profile ->
            item {
                DiagnosticsScanWorkflowCard(
                    profile = profile,
                    scan = scan,
                    strategyProbeSelected = strategyProbeSelected,
                    isFullAudit = profile.strategyProbeSuiteId == "full_matrix_v1",
                    onRunRawScan = onRunRawScan,
                    onRunInPathScan = onRunInPathScan,
                    onCancelScan = onCancelScan,
                    onOpenAdvancedSettings = onOpenAdvancedSettings,
                    onOpenDnsSettings = onOpenDnsSettings,
                    onRequestVpnPermission = onRequestVpnPermission,
                    onOpenHistory = onOpenHistory,
                    onOpenModeEditor = onOpenModeEditor,
                    onOpenOwnedStackBrowser = onOpenOwnedStackBrowser,
                    modifier = Modifier.ripDpiTestTag(scanStateTag),
                )
            }
        }
        scan.activeProgress?.let { progress ->
            item {
                ScanProgressCard(
                    progress = progress,
                    strategyProbeSelected = strategyProbeSelected,
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsScanProgressCard),
                )
            }
            if (progress.completedProbes.isNotEmpty()) {
                item {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_live_results_title))
                }
                itemsIndexed(
                    items = progress.completedProbes.reversed().take(LiveProbePreviewCount),
                    key = { _, probe -> "${probe.target}-${probe.outcome}" },
                    contentType = { _, _ -> "live_probe" },
                ) { _, probe ->
                    AnimatedVisibility(
                        visible = true,
                        enter =
                            if (motion.animationsEnabled) {
                                fadeIn(animationSpec = motion.stateTween()) +
                                    slideInVertically(
                                        animationSpec = motion.stateTween(),
                                    ) { it / 2 }
                            } else {
                                EnterTransition.None
                            },
                    ) {
                        LiveProbeResultRow(
                            probe = probe,
                            modifier =
                                Modifier.ripDpiTestTag(
                                    RipDpiTestTags.diagnosticsLiveProbe("${probe.target}-${probe.outcome}"),
                                ),
                        )
                    }
                }
            }
        }
        scan.latestSession?.let { session ->
            item {
                SettingsCategoryHeader(
                    title =
                        if (strategyProbeSelected) {
                            stringResource(R.string.diagnostics_probe_latest_section)
                        } else {
                            stringResource(R.string.diagnostics_latest_scan_section)
                        },
                )
                SessionRow(
                    session = session,
                    onClick = {},
                    modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsSession(session.id)),
                )
            }
        }
        scan.strategyProbeReport?.let { report ->
            item {
                StrategyProbeReportCard(
                    report = report,
                    onSelectCandidate = onSelectStrategyProbeCandidate,
                )
            }
        }
        if (scan.latestResults.isNotEmpty()) {
            item {
                val sectionTitle =
                    if (strategyProbeSelected) {
                        stringResource(R.string.diagnostics_probe_evidence_section)
                    } else {
                        stringResource(R.string.diagnostics_results_section)
                    }
                RipDpiCard {
                    SettingsCategoryHeader(
                        title = "$sectionTitle (${scan.latestResults.size})",
                    )
                    scan.latestResults.forEach { probe ->
                        if (probe.probeType == "telegram_availability") {
                            TelegramResultCard(
                                probe = probe,
                                onClick = { onSelectProbe(probe) },
                                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsProbe(probe.id)),
                            )
                        } else {
                            CompactProbeRow(
                                probe = probe,
                                onClick = { onSelectProbe(probe) },
                                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsProbe(probe.id)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosisSummaryCard(
    title: String,
    diagnoses: List<DiagnosticsDiagnosisUiModel>,
) {
    TrackRecomposition("DiagnosisSummaryCard")
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Elevated) {
        Text(
            text = title.uppercase(),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            diagnoses.forEach { diagnosis ->
                RipDpiCard(variant = RipDpiCardVariant.Tonal) {
                    StatusIndicator(label = diagnosis.code, tone = statusTone(diagnosis.tone))
                    Text(
                        text = diagnosis.summary,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = colors.foreground,
                    )
                    diagnosis.target?.let { target ->
                        Text(
                            text = target,
                            style = RipDpiThemeTokens.type.monoSmall,
                            color = colors.mutedForeground,
                        )
                    }
                    diagnosis.evidence.take(MaxVisibleEvidence).forEach { evidence ->
                        Text(
                            text = evidence,
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.mutedForeground,
                        )
                    }
                    diagnosis.recommendation?.let { recommendation ->
                        Text(
                            text = recommendation,
                            style = RipDpiThemeTokens.type.secondaryBody,
                            color = colors.accentForeground,
                        )
                    }
                }
            }
        }
    }
}
