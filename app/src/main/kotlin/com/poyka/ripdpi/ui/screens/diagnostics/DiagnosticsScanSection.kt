package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsDiagnosisUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.StrategyProbeSuiteFullMatrixV1
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
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
    onSelectiveMatrixHostsChanged: (String) -> Unit = {},
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
    onSelectSession: (String) -> Unit = {},
    expertMode: Boolean = true,
    compositeRunBusy: Boolean = false,
    admissionBusy: Boolean = false,
) {
    TrackRecomposition("ScanSection")
    val spacing = RipDpiThemeTokens.spacing
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val cancellationAvailable = scan.activeProgress != null || compositeRunBusy
    val effectiveBusy = scan.isBusy || cancellationAvailable || admissionBusy
    val workflowScan =
        scan.copy(
            isBusy = cancellationAvailable,
            runRawEnabled = scan.runRawEnabled && !effectiveBusy,
            runInPathEnabled = scan.runInPathEnabled && !effectiveBusy,
            workflowPresentation =
                if (effectiveBusy &&
                    scan.activeProgress == null
                ) {
                    null
                } else {
                    scan.workflowPresentation
                },
        )
    val selectedProfile = scan.selectedProfile
    val strategyProbeSelected = selectedProfile?.kind == com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE
    // Memoize the reversed + truncated live-probe preview: during an active scan the
    // progress model is re-emitted frequently (the elapsed/fraction fields tick), and
    // recomputing `reversed().take(...)` inline in the LazyListScope below would
    // reallocate and re-sort the list on each recomposition. `remember` cannot be
    // called inside the LazyListScope, so the derivation is hoisted here. The memo is
    // keyed on `completedProbes` specifically so it survives unrelated progress ticks
    // and recomputes only when the probe list actually changes.
    val livePreviewProbes =
        remember(scan.activeProgress?.completedProbes) {
            scan.activeProgress
                ?.completedProbes
                ?.reversed()
                ?.take(LiveProbePreviewCount)
                .orEmpty()
        }
    val visibleResults =
        remember(scan.latestResults, expertMode) {
            scan.latestResults.filter {
                expertMode ||
                    !it.probeType.startsWith("selective_availability")
            }
        }
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
    val profileSearch =
        com.poyka.ripdpi.ui.components.profiles
            .rememberProfileSearchState()
    var showProfilePicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(effectiveBusy) {
        if (effectiveBusy) showProfilePicker = false
    }
    if (showProfilePicker && !effectiveBusy) {
        ProfileSelectionBottomSheet(
            profiles = scan.profiles,
            selectedProfileId = scan.selectedProfileId,
            search = profileSearch,
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
                onChangeProfile = { if (!effectiveBusy) showProfilePicker = true },
                enabled = !effectiveBusy,
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
                    scan = workflowScan,
                    strategyProbeSelected = strategyProbeSelected,
                    isFullAudit = profile.strategyProbeSuiteId == StrategyProbeSuiteFullMatrixV1,
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
        if (selectedProfile == null && cancellationAvailable) {
            item {
                RipDpiButton(
                    text = stringResource(R.string.diagnostics_action_cancel),
                    onClick = onCancelScan,
                    variant = RipDpiButtonVariant.Destructive,
                    modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.DiagnosticsScanCancelAction),
                )
            }
        }
        if (selectedProfile?.id == com.poyka.ripdpi.activities.SelectiveMatrixProfileId) {
            item {
                SelectiveMatrixInputCard(
                    input = scan.selectiveMatrixHostsInput,
                    targets = scan.selectiveMatrixTargets,
                    valid = scan.selectiveMatrixInputValid,
                    enabled = !effectiveBusy,
                    onInputChanged = onSelectiveMatrixHostsChanged,
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
            progress.transferProgress?.let { transfer ->
                item { DiagnosticsTransferCard(transfer = transfer) }
            }
            if (progress.completedProbes.isNotEmpty()) {
                item {
                    SettingsCategoryHeader(title = stringResource(R.string.diagnostics_live_results_title))
                }
                itemsIndexed(
                    items = livePreviewProbes,
                    key = { index, _ -> liveProbeItemKey(progress.completedProbes.size, index) },
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
                    onClick = { onSelectSession(session.id) },
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
        if (scan.latestResults.any { it.probeType.startsWith("selective_availability") }) {
            item { SelectiveMatrixResultsCard(scan.latestResults) }
        }
        scan.latestResults.mapNotNull { it.transferEvidence }.forEach { transfer ->
            item { DiagnosticsTransferCard(transfer = transfer) }
        }
        if (visibleResults.isNotEmpty()) {
            item {
                val sectionTitle =
                    if (strategyProbeSelected) {
                        stringResource(R.string.diagnostics_probe_evidence_section)
                    } else {
                        stringResource(R.string.diagnostics_results_section)
                    }
                RipDpiCard {
                    SettingsCategoryHeader(
                        title =
                            stringResource(
                                R.string.diagnostics_results_section_count,
                                sectionTitle,
                                visibleResults.size,
                            ),
                    )
                    visibleResults.forEach { probe ->
                        if (probe.probeType == "telegram_availability") {
                            TelegramResultCard(
                                probe = probe,
                                onClick = { onSelectProbe(probe) },
                                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsProbe(probe.id)),
                            )
                        } else {
                            CompactProbeRow(
                                probe = probe,
                                expertMode = expertMode,
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

internal fun liveProbeItemKey(
    completedProbeCount: Int,
    previewIndex: Int,
): Int = completedProbeCount - previewIndex - 1

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
