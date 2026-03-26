package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.CompletedProbeUiModel
import com.poyka.ripdpi.activities.DiagnosticsDiagnosisUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsProgressUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsScanUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.PhaseState
import com.poyka.ripdpi.activities.PhaseStepUiModel
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val MaxVisibleEvidence = 3
private const val LiveProbePreviewCount = 8

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun ScanSection(
    scan: DiagnosticsScanUiModel,
    onSelectProfile: (String) -> Unit,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
    onKeepResolverRecommendation: (String?) -> Unit,
    onSaveResolverRecommendation: (String?) -> Unit,
    onSelectStrategyProbeCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
    onSelectProbe: (DiagnosticsProbeResultUiModel) -> Unit,
) {
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = layout.horizontalPadding,
                vertical = spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item { ScanProfilePickerCard(scan = scan, onSelectProfile = onSelectProfile) }
        if (scan.diagnoses.isNotEmpty()) {
            item {
                DiagnosisSummaryCard(
                    title = stringResource(R.string.diagnostics_diagnosis_summary_title),
                    diagnoses = scan.diagnoses,
                )
            }
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
                items(
                    items = progress.completedProbes.reversed().take(LiveProbePreviewCount),
                    key = { "${it.target}-${it.outcome}" },
                ) { probe ->
                    AnimatedVisibility(
                        visible = true,
                        enter =
                            if (motion.animationsEnabled) {
                                fadeIn() + slideInVertically { it / 2 }
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
        scan.resolverRecommendation?.let { recommendation ->
            item {
                ResolverRecommendationCard(
                    recommendation = recommendation,
                    onKeepForSession = { onKeepResolverRecommendation(scan.latestSession?.id) },
                    onSaveAsSetting = { onSaveResolverRecommendation(scan.latestSession?.id) },
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
                SettingsCategoryHeader(
                    title =
                        if (strategyProbeSelected) {
                            stringResource(R.string.diagnostics_probe_evidence_section)
                        } else {
                            stringResource(R.string.diagnostics_results_section)
                        },
                )
            }
            items(
                items = scan.latestResults,
                key = { it.id },
                contentType = { probe ->
                    if (probe.probeType ==
                        "telegram_availability"
                    ) {
                        "telegram_probe"
                    } else {
                        "probe"
                    }
                },
            ) { probe ->
                if (probe.probeType == "telegram_availability") {
                    TelegramResultCard(
                        probe = probe,
                        onClick = { onSelectProbe(probe) },
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsProbe(probe.id)),
                    )
                } else {
                    ProbeResultRow(
                        probe = probe,
                        onClick = { onSelectProbe(probe) },
                        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsProbe(probe.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanProfilePickerCard(
    scan: DiagnosticsScanUiModel,
    onSelectProfile: (String) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(variant = RipDpiCardVariant.Elevated) {
        Text(
            text = stringResource(R.string.diagnostics_profiles_title).uppercase(),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.diagnostics_profiles_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            scan.profiles.groupBy { it.family }.forEach { (family, profiles) ->
                Text(
                    text = family.displayFamilyLabel(),
                    style = RipDpiThemeTokens.type.bodyEmphasis,
                    color = RipDpiThemeTokens.colors.foreground,
                )
                profiles.forEach { profile ->
                    DiagnosticsProfileCard(
                        profile = profile,
                        selected = profile.id == scan.selectedProfileId,
                        onClick = { onSelectProfile(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanProgressCard(
    progress: DiagnosticsProgressUiModel,
    strategyProbeSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val motion = RipDpiThemeTokens.motion
    RipDpiCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIndicator(
                label =
                    if (strategyProbeSelected) {
                        stringResource(R.string.diagnostics_probe_progress_title)
                    } else {
                        stringResource(R.string.diagnostics_status_running)
                    },
                tone = StatusIndicatorTone.Warning,
            )
            Text(
                text = progress.elapsedLabel,
                style = RipDpiThemeTokens.type.monoInline,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        AnimatedContent(
            targetState = progress.currentProbeLabel,
            transitionSpec = {
                androidx.compose.animation.fadeIn(
                    animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
                ) togetherWith
                    androidx.compose.animation.fadeOut(
                        animationSpec = tween(durationMillis = motion.duration(motion.quickDurationMillis)),
                    )
            },
            label = "scanProgressLabel",
        ) { label ->
            Text(
                text = label,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
        }
        if (progress.phaseSteps.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                items(
                    items = progress.phaseSteps,
                    key = { it.label },
                    contentType = { "phase_chip" },
                ) { step ->
                    PhaseChip(step = step)
                }
            }
        }
        LinearProgressIndicator(
            progress = { progress.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${progress.completedSteps}/${progress.totalSteps}",
                style = RipDpiThemeTokens.type.monoInline,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
            progress.etaLabel?.let { eta ->
                Text(
                    text = eta,
                    style = RipDpiThemeTokens.type.monoSmall,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

@Composable
private fun PhaseChip(step: PhaseStepUiModel) {
    val motion = RipDpiThemeTokens.motion
    val palette = metricPalette(step.tone)
    val animatedContainer by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "phaseChipContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "phaseChipContent",
    )
    Surface(
        shape = RipDpiThemeTokens.shapes.full,
        color = animatedContainer,
        contentColor = animatedContent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RipDpiThemeTokens.spacing.sm, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotAlpha =
                when (step.state) {
                    PhaseState.Completed -> 0.72f
                    PhaseState.Active -> 1f
                    PhaseState.Pending -> 0.38f
                }
            Text(
                text =
                    when (step.state) {
                        PhaseState.Completed -> "✓ ${step.label}"
                        else -> step.label
                    },
                style = RipDpiThemeTokens.type.monoSmall,
                color = animatedContent.copy(alpha = dotAlpha),
            )
        }
    }
}

@Composable
private fun LiveProbeResultRow(
    probe: CompletedProbeUiModel,
    modifier: Modifier = Modifier,
) {
    val palette = metricPalette(probe.tone)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = RipDpiThemeTokens.spacing.md, vertical = RipDpiThemeTokens.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIndicator(
            label = probe.outcome,
            tone =
                when (probe.tone) {
                    DiagnosticsTone.Positive -> StatusIndicatorTone.Active
                    DiagnosticsTone.Negative -> StatusIndicatorTone.Error
                    DiagnosticsTone.Warning -> StatusIndicatorTone.Warning
                    else -> StatusIndicatorTone.Idle
                },
        )
        Text(
            text = probe.target,
            style = RipDpiThemeTokens.type.monoInline,
            color = palette.content,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun DiagnosticsProfileCard(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val (badge, description) =
        when {
            profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.WEB_CONNECTIVITY -> {
                stringResource(R.string.diagnostics_profile_badge_ru_web) to
                    stringResource(R.string.diagnostics_profile_desc_web_connectivity)
            }

            profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.MESSAGING -> {
                stringResource(R.string.diagnostics_profile_badge_ru_msg) to
                    stringResource(R.string.diagnostics_profile_desc_messaging)
            }

            profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.CIRCUMVENTION -> {
                stringResource(R.string.diagnostics_profile_badge_ru_adapt) to
                    stringResource(R.string.diagnostics_profile_desc_adaptation)
            }

            profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.THROTTLING -> {
                stringResource(R.string.diagnostics_profile_badge_ru_rate) to
                    stringResource(R.string.diagnostics_profile_desc_throttling)
            }

            profile.family == com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.DPI_FULL -> {
                stringResource(R.string.diagnostics_profile_badge_ru_full) to
                    stringResource(R.string.diagnostics_profile_desc_network_full)
            }

            profile.strategyProbeSuiteId == "full_matrix_v1" -> {
                stringResource(R.string.diagnostics_profile_audit_badge) to
                    stringResource(R.string.diagnostics_profile_audit_body)
            }

            profile.kind == com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE -> {
                stringResource(R.string.diagnostics_profile_probe_badge) to
                    stringResource(R.string.diagnostics_profile_probe_body)
            }

            else -> {
                stringResource(R.string.diagnostics_profile_connectivity_badge) to
                    stringResource(R.string.diagnostics_profile_connectivity_body)
            }
        }

    PresetCard(
        title = profile.name,
        description =
            buildString {
                append(description)
                if (profile.manualOnly) {
                    append(" Manual run only.")
                }
                if (profile.packRefs.isNotEmpty()) {
                    append(" ${profile.packRefs.size} curated packs included.")
                }
            },
        badgeText = if (profile.regionTag?.equals("ru", ignoreCase = true) == true) "$badge · RU" else badge,
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.diagnosticsProfile(profile.id)),
        selected = selected,
        onClick = onClick,
    )
}

@Composable
internal fun ResolverRecommendationCard(
    recommendation: DiagnosticsResolverRecommendationUiModel,
    onKeepForSession: () -> Unit,
    onSaveAsSetting: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsResolverRecommendationCard),
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label =
                if (recommendation.appliedTemporarily) "Temporary DNS override active" else "Encrypted DNS recommended",
            tone =
                if (recommendation.appliedTemporarily) StatusIndicatorTone.Active else StatusIndicatorTone.Warning,
        )
        Text(
            text = recommendation.headline,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = recommendation.rationale,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        recommendation.fields.forEach { field ->
            SettingsRow(
                title = field.label,
                value = field.value,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = "Keep for this session",
                onClick = onKeepForSession,
                variant = RipDpiButtonVariant.Secondary,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.DiagnosticsResolverKeepSession),
            )
            if (recommendation.persistable) {
                RipDpiButton(
                    text = "Save as DNS setting",
                    onClick = onSaveAsSetting,
                    variant = RipDpiButtonVariant.Primary,
                    modifier =
                        Modifier
                            .weight(1f)
                            .ripDpiTestTag(RipDpiTestTags.DiagnosticsResolverSaveSetting),
                )
            }
        }
    }
}

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
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val status = workflowStatus(scan, strategyProbeSelected, isFullAudit)
    val badges = workflowBadges(profile, strategyProbeSelected, isFullAudit)

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
    ) {
        StatusIndicator(label = status.title, tone = status.tone)
        Text(
            text = profile.name,
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        Text(
            text = status.body,
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(badges, key = { it.text }) { badge ->
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
            isFullAudit = isFullAudit,
            scan = scan,
            spacing = spacing.sm,
            onRunRawScan = onRunRawScan,
            onRunInPathScan = onRunInPathScan,
        )
        if (scan.isBusy) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_action_cancel),
                onClick = onCancelScan,
                variant = RipDpiButtonVariant.Destructive,
                modifier = Modifier
                    .fillMaxWidth()
                    .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanCancelAction),
            )
        }
    }
}

private data class WorkflowStatusUiModel(
    val title: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class WorkflowBadgeUiModel(
    val text: String,
    val tone: DiagnosticsTone,
)

@Composable
private fun workflowStatus(
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): WorkflowStatusUiModel =
    when {
        scan.isBusy && isFullAudit -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_progress_title),
                body = stringResource(R.string.diagnostics_profile_audit_running_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        scan.isBusy && strategyProbeSelected -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_progress_title),
                body = stringResource(R.string.diagnostics_profile_probe_running_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        scan.isBusy -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_status_running),
                body = stringResource(R.string.diagnostics_profile_connectivity_running_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        isFullAudit && !scan.runRawEnabled -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_unavailable_title),
                body = stringResource(R.string.diagnostics_profile_audit_unavailable_body),
                tone = StatusIndicatorTone.Error,
            )
        }

        strategyProbeSelected && !scan.runRawEnabled -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_unavailable_title),
                body = stringResource(R.string.diagnostics_profile_probe_unavailable_body),
                tone = StatusIndicatorTone.Error,
            )
        }

        isFullAudit && scan.strategyProbeReport != null -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_ready_title),
                body = stringResource(R.string.diagnostics_profile_audit_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        strategyProbeSelected && scan.strategyProbeReport != null -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_ready_title),
                body = stringResource(R.string.diagnostics_profile_probe_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        isFullAudit -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_audit_profile_title),
                body = stringResource(R.string.diagnostics_profile_audit_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        strategyProbeSelected -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_probe_profile_title),
                body = stringResource(R.string.diagnostics_profile_probe_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        else -> {
            WorkflowStatusUiModel(
                title = stringResource(R.string.diagnostics_profile_connectivity_title),
                body = stringResource(R.string.diagnostics_profile_connectivity_body),
                tone = StatusIndicatorTone.Idle,
            )
        }
    }

@Composable
private fun workflowBadges(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
): List<WorkflowBadgeUiModel> =
    buildList {
        if (isFullAudit) {
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_http_https_quic),
                    DiagnosticsTone.Info,
                ),
            )
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_all_builtin),
                    DiagnosticsTone.Warning,
                ),
            )
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_raw_only),
                    DiagnosticsTone.Warning,
                ),
            )
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_manual_apply),
                    DiagnosticsTone.Positive,
                ),
            )
        } else if (strategyProbeSelected) {
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_http_https_quic),
                    DiagnosticsTone.Info,
                ),
            )
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_raw_only),
                    DiagnosticsTone.Warning,
                ),
            )
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_manual_apply),
                    DiagnosticsTone.Positive,
                ),
            )
        } else {
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_dns_http_https_tcp),
                    DiagnosticsTone.Info,
                ),
            )
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_raw_and_in_path),
                    DiagnosticsTone.Positive,
                ),
            )
        }
        if (profile.manualOnly) {
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_manual_only),
                    DiagnosticsTone.Warning,
                ),
            )
        }
        if (profile.regionTag?.equals("ru", ignoreCase = true) == true) {
            add(
                WorkflowBadgeUiModel(
                    stringResource(R.string.diagnostics_profile_badge_region_net),
                    DiagnosticsTone.Warning,
                ),
            )
        }
    }

@Composable
private fun WorkflowActionRow(
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    spacing: androidx.compose.ui.unit.Dp,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
) {
    if (strategyProbeSelected) {
        RipDpiButton(
            text = rawActionLabel(scan, isFullAudit),
            onClick = onRunRawScan,
            modifier = Modifier
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
            text = stringResource(R.string.diagnostics_action_raw),
            onClick = onRunRawScan,
            modifier = Modifier
                .weight(1f)
                .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunRawAction),
            enabled = scan.runRawEnabled,
        )
        RipDpiButton(
            text = stringResource(R.string.diagnostics_action_in_path),
            onClick = onRunInPathScan,
            modifier = Modifier
                .weight(1f)
                .ripDpiTestTag(RipDpiTestTags.DiagnosticsScanRunInPathAction),
            variant = RipDpiButtonVariant.Outline,
            enabled = scan.runInPathEnabled,
        )
    }
}

@Composable
private fun rawActionLabel(
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    isFullAudit: Boolean,
): String =
    when {
        !scan.runRawEnabled && isFullAudit -> stringResource(R.string.diagnostics_action_audit_unavailable)
        !scan.runRawEnabled -> stringResource(R.string.diagnostics_action_probe_unavailable)
        isFullAudit -> stringResource(R.string.diagnostics_action_start_audit)
        else -> stringResource(R.string.diagnostics_action_start_probe)
    }

@Composable
private fun DiagnosisSummaryCard(
    title: String,
    diagnoses: List<DiagnosticsDiagnosisUiModel>,
) {
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
                }
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.displayFamilyLabel(): String =
    when (this) {
        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.GENERAL -> {
            stringResource(
                R.string.diagnostics_family_general,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.WEB_CONNECTIVITY -> {
            stringResource(
                R.string.diagnostics_family_web_connectivity,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.MESSAGING -> {
            stringResource(
                R.string.diagnostics_family_messaging,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.CIRCUMVENTION -> {
            stringResource(
                R.string.diagnostics_family_adaptation,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.THROTTLING -> {
            stringResource(
                R.string.diagnostics_family_throttling,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.DPI_FULL -> {
            stringResource(
                R.string.diagnostics_family_network_full,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_PROBING -> {
            stringResource(
                R.string.diagnostics_family_automatic_probing,
            )
        }

        com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily.AUTOMATIC_AUDIT -> {
            stringResource(
                R.string.diagnostics_family_automatic_audit,
            )
        }
    }

@Composable
internal fun StrategyProbeReportCard(
    report: DiagnosticsStrategyProbeReportUiModel,
    onSelectCandidate: (DiagnosticsStrategyProbeCandidateDetailUiModel) -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    val colors = RipDpiThemeTokens.colors
    val manualApplyBadge = stringResource(R.string.diagnostics_profile_badge_manual_apply)
    val isFullAudit = report.suiteId == "full_matrix_v1"
    RipDpiCard(
        modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyProbeReport),
    ) {
        StatusIndicator(
            label =
                if (isFullAudit) {
                    stringResource(R.string.diagnostics_audit_ready_title)
                } else {
                    stringResource(R.string.diagnostics_probe_ready_title)
                },
            tone = StatusIndicatorTone.Active,
        )
        Text(
            text =
                if (isFullAudit) {
                    stringResource(R.string.diagnostics_audit_matrix_title)
                } else {
                    stringResource(R.string.diagnostics_probe_recommendation_title)
                }.uppercase(),
            style = RipDpiThemeTokens.type.sectionTitle,
            color = colors.foreground,
        )
        Text(
            text = report.recommendation.headline,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = report.recommendation.rationale,
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(
                listOf(
                    report.suiteLabel to DiagnosticsTone.Info,
                    manualApplyBadge to DiagnosticsTone.Positive,
                ),
            ) { badge ->
                EventBadge(text = badge.first, tone = badge.second)
            }
        }
        if (report.summaryMetrics.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.diagnostics_audit_summary_title),
                modifier = Modifier.ripDpiTestTag(RipDpiTestTags.DiagnosticsStrategyProbeSummary),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            MetricsRow(metrics = report.summaryMetrics)
            Text(
                text = stringResource(R.string.diagnostics_audit_candidate_open_hint),
                style = RipDpiThemeTokens.type.secondaryBody,
                color = colors.mutedForeground,
            )
        }
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
        report.recommendation.fields.forEach { field ->
            SettingsRow(
                title = field.label,
                value = field.value,
            )
        }
        if (report.recommendation.signature.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.diagnostics_probe_signature_title),
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
            )
            report.recommendation.signature.forEach { field ->
                SettingsRow(
                    title = field.label,
                    value = field.value,
                )
            }
        }
        report.families.forEach { family ->
            HorizontalDivider()
            Text(
                text = family.title,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = colors.foreground,
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
}

@Composable
internal fun StrategyProbeCandidateRow(
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
                        modifier.clickable(onClick = onClick)
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
                            candidate.recommended -> stringResource(R.string.diagnostics_probe_status_recommended)
                            candidate.skipped -> stringResource(R.string.diagnostics_probe_status_skipped)
                            else -> candidate.outcome
                        },
                    tone =
                        if (candidate.recommended) {
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
