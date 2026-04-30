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

private const val SparklineBarWidthPerTarget = 4
private const val SparklineMaxBarWidth = 24
private const val PhaseStepActiveAlpha = 1f
private const val PhaseStepCompletedAlpha = 0.72f
private const val PhaseStepPendingAlpha = 0.38f

@Suppress("LongMethod")
@Composable
internal fun ScanProgressCard(
    progress: DiagnosticsProgressUiModel,
    strategyProbeSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("ScanProgressCard")
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
        progress.strategyProbeProgress?.let { liveProgress ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIndicator(
                    label =
                        when (liveProgress.lane) {
                            DiagnosticsStrategyProbeProgressLaneUiModel.TCP -> {
                                stringResource(R.string.diagnostics_phase_tcp)
                            }

                            DiagnosticsStrategyProbeProgressLaneUiModel.QUIC -> {
                                stringResource(R.string.diagnostics_phase_quic)
                            }
                        },
                    tone = StatusIndicatorTone.Warning,
                )
                Text(
                    text = "${liveProgress.candidateIndex}/${liveProgress.candidateTotal}",
                    style = RipDpiThemeTokens.type.monoInline,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
        progress.dnsBaselineStatus?.let { dnsStatus ->
            DnsBaselineBadge(status = dnsStatus)
        }
        progress.dpiFailureClass?.let { failureClass ->
            DpiFailureClassBadge(failureClass = failureClass)
        }
        if (progress.candidateTimeline.isNotEmpty()) {
            CandidateTimeline(entries = progress.candidateTimeline)
        }
        AnimatedContent(
            targetState = progress.currentProbeLabel,
            transitionSpec = {
                fadeIn(animationSpec = motion.stateTween()) togetherWith
                    fadeOut(animationSpec = motion.quickTween())
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
        progress.networkContext?.let { ctx ->
            NetworkContextRow(context = ctx)
        }
    }
}

@Composable
private fun PhaseChip(step: PhaseStepUiModel) {
    RipDpiMetricSurface(
        tone = metricTone(step.tone),
        shape = RipDpiThemeTokens.shapes.full,
    ) { contentColor ->
        Row(
            modifier = Modifier.padding(horizontal = RipDpiThemeTokens.spacing.sm, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotAlpha =
                when (step.state) {
                    PhaseState.Completed -> PhaseStepCompletedAlpha
                    PhaseState.Active -> PhaseStepActiveAlpha
                    PhaseState.Pending -> PhaseStepPendingAlpha
                }
            Text(
                text =
                    when (step.state) {
                        PhaseState.Completed -> "✓ ${step.label}"
                        else -> step.label
                    },
                style = RipDpiThemeTokens.type.monoSmall,
                color = contentColor.copy(alpha = dotAlpha),
            )
        }
    }
}

@Composable
private fun DnsBaselineBadge(status: DnsBaselineStatus) {
    val tone =
        when (status) {
            DnsBaselineStatus.CLEAN -> DiagnosticsTone.Positive
            DnsBaselineStatus.TAMPERED -> DiagnosticsTone.Warning
        }
    RipDpiMetricPill(
        text =
            when (status) {
                DnsBaselineStatus.CLEAN -> "DNS: Clean"
                DnsBaselineStatus.TAMPERED -> "DNS: Tampered (DoH fallback)"
            },
        tone = metricTone(tone),
        shape = RipDpiThemeTokens.shapes.full,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.spacing.sm,
                vertical = 4.dp,
            ),
    )
}

@Composable
private fun DpiFailureClassBadge(failureClass: DpiFailureClass) {
    RipDpiMetricPill(
        text = "DPI: ${failureClass.label}",
        tone = RipDpiMetricTone.Negative,
        shape = RipDpiThemeTokens.shapes.full,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.spacing.sm,
                vertical = 4.dp,
            ),
    )
}

@Composable
private fun NetworkContextRow(context: ScanNetworkContextUiModel) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        NetworkContextChip(
            text = context.transport + (context.signalLabel?.let { " $it" } ?: ""),
            tone = RipDpiMetricTone.Info,
        )
        context.resolverLabel?.let { resolver ->
            NetworkContextChip(text = resolver, tone = RipDpiMetricTone.Info)
        }
        NetworkContextChip(
            text = if (context.validated) "Validated" else "Not validated",
            tone = if (context.validated) RipDpiMetricTone.Positive else RipDpiMetricTone.Neutral,
        )
    }
}

@Composable
private fun NetworkContextChip(
    text: String,
    tone: RipDpiMetricTone,
) {
    RipDpiMetricPill(
        text = text,
        tone = tone,
        shape = RipDpiThemeTokens.shapes.full,
        paddingValues =
            PaddingValues(
                horizontal = RipDpiThemeTokens.spacing.sm,
                vertical = 4.dp,
            ),
    )
}

@Composable
private fun CandidateTimeline(entries: List<StrategyCandidateTimelineEntryUiModel>) {
    val spacing = RipDpiThemeTokens.spacing
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        items(
            items = entries,
            key = { it.candidateId },
            contentType = { "candidate_dot" },
        ) { entry ->
            CandidateTimelineChip(entry = entry)
        }
    }
}

@Composable
private fun CandidateTimelineChip(entry: StrategyCandidateTimelineEntryUiModel) {
    RipDpiMetricSurface(
        tone = metricTone(entry.tone),
        shape = RipDpiThemeTokens.shapes.full,
    ) { contentColor ->
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when {
                        entry.outcome.equals("success", ignoreCase = true) -> "+"

                        entry.outcome.equals("skipped", ignoreCase = true) ||
                            entry.outcome.equals("not_applicable", ignoreCase = true) -> "-"

                        else -> "x"
                    },
                style = RipDpiThemeTokens.type.monoSmall,
            )
            if (entry.totalTargets > 0) {
                CandidateSparkline(
                    succeeded = entry.succeededTargets,
                    total = entry.totalTargets,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun CandidateSparkline(
    succeeded: Int,
    total: Int,
    color: androidx.compose.ui.graphics.Color,
) {
    val barWidth = (total * SparklineBarWidthPerTarget).coerceAtMost(SparklineMaxBarWidth)
    val fraction = if (total > 0) succeeded.toFloat() / total.toFloat() else 0f
    Canvas(
        modifier = Modifier.size(width = barWidth.dp, height = 6.dp),
    ) {
        drawRoundRect(
            color = color.copy(alpha = 0.25f),
            size = size,
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(3.dp.toPx()),
        )
        if (fraction > 0f) {
            drawRoundRect(
                color = color,
                size = size.copy(width = size.width * fraction),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(3.dp.toPx()),
            )
        }
    }
}

@Composable
internal fun LiveProbeResultRow(
    probe: CompletedProbeUiModel,
    modifier: Modifier = Modifier,
) {
    TrackRecomposition("LiveProbeResultRow")
    val metricStyle = ripDpiMetricToneStyle(metricTone(probe.tone))
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
            color = metricStyle.content,
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
                if (profile.requiresExplicitConsent) {
                    append(" ${stringResource(R.string.diagnostics_profile_explicit_consent_required)}")
                }
                if (profile.packRefs.isNotEmpty()) {
                    append(" ${profile.packRefs.size} curated packs included.")
                }
            },
        badgeText =
            buildString {
                append(if (profile.regionTag?.equals("ru", ignoreCase = true) == true) "$badge · RU" else badge)
                if (profile.requiresExplicitConsent) {
                    append(" · ")
                    append(stringResource(R.string.diagnostics_profile_badge_consent))
                }
            },
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
    TrackRecomposition("ResolverRecommendationCard")
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
