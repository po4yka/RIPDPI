package com.poyka.ripdpi.ui.screens.diagnostics

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsProbeResultUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateDetailUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeCandidateUiModel
import com.poyka.ripdpi.activities.DiagnosticsStrategyProbeReportUiModel
import com.poyka.ripdpi.activities.DiagnosticsResolverRecommendationUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.activities.DiagnosticsUiState
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
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun ScanSection(
    uiState: DiagnosticsUiState,
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
    val selectedProfile = uiState.scan.selectedProfile
    val strategyProbeSelected = selectedProfile?.kind == com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = layout.horizontalPadding, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            RipDpiCard(variant = RipDpiCardVariant.Elevated) {
                Text(
                    text = stringResource(R.string.diagnostics_profiles_title),
                    style = RipDpiThemeTokens.type.sectionTitle,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Text(
                    text = stringResource(R.string.diagnostics_profiles_body),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    uiState.scan.profiles.forEach { profile ->
                        DiagnosticsProfileCard(
                            profile = profile,
                            selected = profile.id == uiState.scan.selectedProfileId,
                            onClick = { onSelectProfile(profile.id) },
                        )
                    }
                }
            }
        }
        selectedProfile?.let { profile ->
            item {
                DiagnosticsScanWorkflowCard(
                    profile = profile,
                    scan = uiState.scan,
                    strategyProbeSelected = strategyProbeSelected,
                    isFullAudit = profile.strategyProbeSuiteId == "full_matrix_v1",
                    onRunRawScan = onRunRawScan,
                    onRunInPathScan = onRunInPathScan,
                    onCancelScan = onCancelScan,
                )
            }
        }
        uiState.scan.activeProgress?.let { progress ->
            item {
                RipDpiCard {
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
                        text = progress.summary,
                        style = RipDpiThemeTokens.type.bodyEmphasis,
                        color = RipDpiThemeTokens.colors.foreground,
                    )
                    LinearProgressIndicator(
                        progress = { progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${progress.completedSteps}/${progress.totalSteps} · ${progress.phase}",
                        style = RipDpiThemeTokens.type.monoInline,
                        color = RipDpiThemeTokens.colors.mutedForeground,
                    )
                }
            }
        }
        uiState.scan.latestSession?.let { session ->
            item {
                SettingsCategoryHeader(
                    title =
                        if (strategyProbeSelected) {
                            stringResource(R.string.diagnostics_probe_latest_section)
                        } else {
                            stringResource(R.string.diagnostics_latest_scan_section)
                        },
                )
                SessionRow(session = session, onClick = {})
            }
        }
        uiState.scan.resolverRecommendation?.let { recommendation ->
            item {
                ResolverRecommendationCard(
                    recommendation = recommendation,
                    onKeepForSession = { onKeepResolverRecommendation(uiState.scan.latestSession?.id) },
                    onSaveAsSetting = { onSaveResolverRecommendation(uiState.scan.latestSession?.id) },
                )
            }
        }
        uiState.scan.strategyProbeReport?.let { report ->
            item {
                StrategyProbeReportCard(
                    report = report,
                    onSelectCandidate = onSelectStrategyProbeCandidate,
                )
            }
        }
        if (uiState.scan.latestResults.isNotEmpty()) {
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
            items(uiState.scan.latestResults, key = { it.id }) { probe ->
                ProbeResultRow(probe = probe, onClick = { onSelectProbe(probe) })
            }
        }
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
            profile.strategyProbeSuiteId == "full_matrix_v1" ->
                stringResource(R.string.diagnostics_profile_audit_badge) to
                    stringResource(R.string.diagnostics_profile_audit_body)

            profile.kind == com.poyka.ripdpi.diagnostics.ScanKind.STRATEGY_PROBE ->
                stringResource(R.string.diagnostics_profile_probe_badge) to
                    stringResource(R.string.diagnostics_profile_probe_body)

            else ->
                stringResource(R.string.diagnostics_profile_connectivity_badge) to
                    stringResource(R.string.diagnostics_profile_connectivity_body)
        }

    PresetCard(
        title = profile.name,
        description = description,
        badgeText = badge,
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
    RipDpiCard(variant = RipDpiCardVariant.Elevated) {
        StatusIndicator(
            label = if (recommendation.appliedTemporarily) "Temporary DNS override active" else "Encrypted DNS recommended",
            tone = if (recommendation.appliedTemporarily) StatusIndicatorTone.Active else StatusIndicatorTone.Warning,
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
                modifier = Modifier.weight(1f).testTag("resolver-keep-session"),
            )
            if (recommendation.persistable) {
                RipDpiButton(
                    text = "Save as DNS setting",
                    onClick = onSaveAsSetting,
                    variant = RipDpiButtonVariant.Primary,
                    modifier = Modifier.weight(1f).testTag("resolver-save-setting"),
                )
            }
        }
    }
}

@Composable
internal fun DiagnosticsScanWorkflowCard(
    profile: com.poyka.ripdpi.activities.DiagnosticsProfileOptionUiModel,
    scan: com.poyka.ripdpi.activities.DiagnosticsScanUiModel,
    strategyProbeSelected: Boolean,
    isFullAudit: Boolean,
    onRunRawScan: () -> Unit,
    onRunInPathScan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val status =
        when {
            scan.isBusy && isFullAudit ->
                Triple(
                    stringResource(R.string.diagnostics_audit_progress_title),
                    stringResource(R.string.diagnostics_profile_audit_running_body),
                    StatusIndicatorTone.Warning,
                )

            scan.isBusy && strategyProbeSelected ->
                Triple(
                    stringResource(R.string.diagnostics_probe_progress_title),
                    stringResource(R.string.diagnostics_profile_probe_running_body),
                    StatusIndicatorTone.Warning,
                )

            scan.isBusy ->
                Triple(
                    stringResource(R.string.diagnostics_status_running),
                    stringResource(R.string.diagnostics_profile_connectivity_running_body),
                    StatusIndicatorTone.Warning,
                )

            isFullAudit && !scan.runRawEnabled ->
                Triple(
                    stringResource(R.string.diagnostics_audit_unavailable_title),
                    stringResource(R.string.diagnostics_profile_audit_unavailable_body),
                    StatusIndicatorTone.Error,
                )

            strategyProbeSelected && !scan.runRawEnabled ->
                Triple(
                    stringResource(R.string.diagnostics_probe_unavailable_title),
                    stringResource(R.string.diagnostics_profile_probe_unavailable_body),
                    StatusIndicatorTone.Error,
                )

            isFullAudit && scan.strategyProbeReport != null ->
                Triple(
                    stringResource(R.string.diagnostics_audit_ready_title),
                    stringResource(R.string.diagnostics_profile_audit_ready_body),
                    StatusIndicatorTone.Active,
                )

            strategyProbeSelected && scan.strategyProbeReport != null ->
                Triple(
                    stringResource(R.string.diagnostics_probe_ready_title),
                    stringResource(R.string.diagnostics_profile_probe_ready_body),
                    StatusIndicatorTone.Active,
                )

            isFullAudit ->
                Triple(
                    stringResource(R.string.diagnostics_audit_profile_title),
                    stringResource(R.string.diagnostics_profile_audit_body),
                    StatusIndicatorTone.Idle,
                )

            strategyProbeSelected ->
                Triple(
                    stringResource(R.string.diagnostics_probe_profile_title),
                    stringResource(R.string.diagnostics_profile_probe_body),
                    StatusIndicatorTone.Idle,
                )

            else ->
                Triple(
                    stringResource(R.string.diagnostics_profile_connectivity_title),
                    stringResource(R.string.diagnostics_profile_connectivity_body),
                    StatusIndicatorTone.Idle,
                )
        }
    val badges =
        buildList {
            if (isFullAudit) {
                add(stringResource(R.string.diagnostics_profile_badge_http_https_quic) to DiagnosticsTone.Info)
                add(stringResource(R.string.diagnostics_profile_badge_all_builtin) to DiagnosticsTone.Warning)
                add(stringResource(R.string.diagnostics_profile_badge_raw_only) to DiagnosticsTone.Warning)
                add(stringResource(R.string.diagnostics_profile_badge_manual_apply) to DiagnosticsTone.Positive)
            } else if (strategyProbeSelected) {
                add(stringResource(R.string.diagnostics_profile_badge_http_https_quic) to DiagnosticsTone.Info)
                add(stringResource(R.string.diagnostics_profile_badge_raw_only) to DiagnosticsTone.Warning)
                add(stringResource(R.string.diagnostics_profile_badge_manual_apply) to DiagnosticsTone.Positive)
            } else {
                add(stringResource(R.string.diagnostics_profile_badge_dns_http_https_tcp) to DiagnosticsTone.Info)
                add(stringResource(R.string.diagnostics_profile_badge_raw_and_in_path) to DiagnosticsTone.Positive)
            }
        }

    RipDpiCard(variant = RipDpiCardVariant.Tonal) {
        StatusIndicator(label = status.first, tone = status.third)
        Text(
            text = profile.name,
            style = RipDpiThemeTokens.type.screenTitle,
            color = colors.foreground,
        )
        Text(
            text = status.second,
            style = RipDpiThemeTokens.type.body,
            color = colors.foreground,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(badges) { badge ->
                EventBadge(text = badge.first, tone = badge.second)
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
        if (strategyProbeSelected) {
            RipDpiButton(
                text =
                    if (!scan.runRawEnabled && isFullAudit) {
                        stringResource(R.string.diagnostics_action_audit_unavailable)
                    } else if (!scan.runRawEnabled) {
                        stringResource(R.string.diagnostics_action_probe_unavailable)
                    } else if (isFullAudit) {
                        stringResource(R.string.diagnostics_action_start_audit)
                    } else {
                        stringResource(R.string.diagnostics_action_start_probe)
                    },
                onClick = onRunRawScan,
                modifier = Modifier.fillMaxWidth(),
                enabled = scan.runRawEnabled,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                RipDpiButton(
                    text = stringResource(R.string.diagnostics_action_raw),
                    onClick = onRunRawScan,
                    modifier = Modifier.weight(1f),
                    enabled = scan.runRawEnabled,
                )
                RipDpiButton(
                    text = stringResource(R.string.diagnostics_action_in_path),
                    onClick = onRunInPathScan,
                    modifier = Modifier.weight(1f),
                    variant = RipDpiButtonVariant.Outline,
                    enabled = scan.runInPathEnabled,
                )
            }
        }
        if (scan.isBusy) {
            RipDpiButton(
                text = stringResource(R.string.diagnostics_action_cancel),
                onClick = onCancelScan,
                variant = RipDpiButtonVariant.Destructive,
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    RipDpiCard {
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
                },
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
                        onClick = report.candidateDetails[candidate.id]?.let { detail ->
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
