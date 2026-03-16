package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeAdaptive
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeCustom
import com.poyka.ripdpi.activities.AdaptiveFakeTtlModeFixed
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private data class AdaptiveFakeTtlStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

@Composable
internal fun rememberAdaptiveFakeTtlModeOptions(
    uiState: SettingsUiState,
    includeCustom: Boolean = uiState.hasCustomAdaptiveFakeTtl,
): List<AdaptiveFakeTtlModeUiModel> =
    buildList {
        add(
            AdaptiveFakeTtlModeUiModel(
                value = AdaptiveFakeTtlModeFixed,
                title = stringResource(R.string.adaptive_fake_ttl_mode_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_mode_fixed_body),
            ),
        )
        add(
            AdaptiveFakeTtlModeUiModel(
                value = AdaptiveFakeTtlModeAdaptive,
                title = stringResource(R.string.adaptive_fake_ttl_mode_adaptive_title),
                body = stringResource(R.string.adaptive_fake_ttl_mode_adaptive_body),
                badgeLabel = stringResource(R.string.adaptive_fake_ttl_mode_recommended),
            ),
        )
        if (includeCustom) {
            add(
                1,
                AdaptiveFakeTtlModeUiModel(
                    value = AdaptiveFakeTtlModeCustom,
                    title = stringResource(R.string.adaptive_fake_ttl_mode_custom_title),
                    body =
                        stringResource(
                            R.string.adaptive_fake_ttl_mode_custom_body,
                            uiState.adaptiveFakeTtlDelta,
                        ),
                    badgeLabel = stringResource(R.string.adaptive_fake_ttl_mode_custom_badge),
                    badgeTone = StatusIndicatorTone.Warning,
                ),
            )
        }
    }

@Composable
internal fun AdaptiveFakeTtlProfileCard(
    uiState: SettingsUiState,
    onResetAdaptiveFakeTtlProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberAdaptiveFakeTtlStatus(uiState)
    val modeSummary =
        when (uiState.adaptiveFakeTtlMode) {
            AdaptiveFakeTtlModeAdaptive -> stringResource(R.string.adaptive_fake_ttl_summary_mode_adaptive)
            AdaptiveFakeTtlModeCustom ->
                stringResource(R.string.adaptive_fake_ttl_summary_mode_custom, uiState.adaptiveFakeTtlDelta)
            else -> stringResource(R.string.adaptive_fake_ttl_summary_mode_fixed)
        }
    val windowSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(
                R.string.adaptive_fake_ttl_summary_window_value,
                uiState.adaptiveFakeTtlMin,
                uiState.adaptiveFakeTtlMax,
            )
        } else {
            stringResource(R.string.adaptive_fake_ttl_summary_window_fixed)
        }
    val fallbackSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(R.string.adaptive_fake_ttl_summary_fallback_value, uiState.adaptiveFakeTtlFallback)
        } else {
            stringResource(R.string.adaptive_fake_ttl_summary_fallback_fixed, uiState.fakeTtl)
        }
    val scopeSummary =
        when {
            uiState.enableCmdSettings -> stringResource(R.string.adaptive_fake_ttl_scope_cli)
            !uiState.fakeTtlControlsRelevant -> stringResource(R.string.adaptive_fake_ttl_scope_idle)
            uiState.hasAdaptiveFakeTtl -> stringResource(R.string.adaptive_fake_ttl_scope_adaptive)
            else -> stringResource(R.string.adaptive_fake_ttl_scope_fixed)
        }
    val targetLabels =
        buildList {
            if (uiState.isFake) add(stringResource(R.string.adaptive_fake_ttl_targets_fake))
            if (uiState.hasHostFake) add(stringResource(R.string.adaptive_fake_ttl_targets_hostfake))
            if (uiState.hasDisoob) add(stringResource(R.string.adaptive_fake_ttl_targets_disoob))
        }
    val targetSummary =
        if (targetLabels.isEmpty()) {
            stringResource(R.string.adaptive_fake_ttl_targets_none)
        } else {
            targetLabels.joinToString()
        }
    val learningSummary =
        if (uiState.hasAdaptiveFakeTtl) {
            stringResource(R.string.adaptive_fake_ttl_learning_runtime)
        } else {
            stringResource(R.string.adaptive_fake_ttl_learning_fixed)
        }
    val badges =
        buildList {
            add(
                (
                    when (uiState.adaptiveFakeTtlMode) {
                        AdaptiveFakeTtlModeAdaptive -> stringResource(R.string.adaptive_fake_ttl_badge_adaptive)
                        AdaptiveFakeTtlModeCustom -> stringResource(R.string.adaptive_fake_ttl_badge_custom)
                        else -> stringResource(R.string.adaptive_fake_ttl_badge_fixed)
                    }
                ) to
                    when (uiState.adaptiveFakeTtlMode) {
                        AdaptiveFakeTtlModeAdaptive -> SummaryCapsuleTone.Active
                        AdaptiveFakeTtlModeCustom -> SummaryCapsuleTone.Info
                        else -> SummaryCapsuleTone.Neutral
                    },
            )
            add(stringResource(R.string.adaptive_fake_ttl_badge_tcp_only) to SummaryCapsuleTone.Info)
            if (uiState.hasAdaptiveFakeTtl) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_runtime_learned) to SummaryCapsuleTone.Active)
            }
            if (uiState.isFake) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_fake) to SummaryCapsuleTone.Active)
            }
            if (uiState.hasHostFake) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_hostfake) to SummaryCapsuleTone.Info)
            }
            if (uiState.hasDisoob) {
                add(stringResource(R.string.adaptive_fake_ttl_badge_disoob) to SummaryCapsuleTone.Warning)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(
            label = status.label,
            tone = status.tone,
        )
        Text(
            text = status.body,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_mode),
                value = modeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_window),
                value = windowSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_fallback),
                value = fallbackSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_scope),
                value = scopeSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_targets),
                value = targetSummary,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fake_ttl_summary_label_learning),
                value = learningSummary,
            )
        }
        if (uiState.canResetAdaptiveFakeTtlProfile) {
            RipDpiButton(
                text = stringResource(R.string.adaptive_fake_ttl_reset_action),
                onClick = onResetAdaptiveFakeTtlProfile,
                variant = RipDpiButtonVariant.Outline,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun rememberAdaptiveFakeTtlStatus(uiState: SettingsUiState): AdaptiveFakeTtlStatusContent =
    when {
        uiState.enableCmdSettings ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_cli_title),
                body = stringResource(R.string.adaptive_fake_ttl_cli_body),
                tone = StatusIndicatorTone.Warning,
            )

        !uiState.fakeTtlControlsRelevant && uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_saved_title),
                body = stringResource(R.string.adaptive_fake_ttl_saved_body),
                tone = StatusIndicatorTone.Idle,
            )

        !uiState.fakeTtlControlsRelevant ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                tone = StatusIndicatorTone.Idle,
            )

        uiState.isServiceRunning && uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_restart_title),
                body = stringResource(R.string.adaptive_fake_ttl_restart_body),
                tone = StatusIndicatorTone.Warning,
            )

        uiState.hasCustomAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_custom_title),
                body = stringResource(R.string.adaptive_fake_ttl_custom_body),
                tone = StatusIndicatorTone.Active,
            )

        uiState.hasAdaptiveFakeTtl ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_adaptive_title),
                body = stringResource(R.string.adaptive_fake_ttl_adaptive_body),
                tone = StatusIndicatorTone.Active,
            )

        else ->
            AdaptiveFakeTtlStatusContent(
                label = stringResource(R.string.adaptive_fake_ttl_fixed_title),
                body = stringResource(R.string.adaptive_fake_ttl_fixed_body),
                tone = StatusIndicatorTone.Idle,
            )
    }

@Composable
internal fun AdaptiveFakeTtlModeSelector(
    uiState: SettingsUiState,
    presets: List<AdaptiveFakeTtlModeUiModel>,
    enabled: Boolean,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.adaptive_fake_ttl_selector_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.adaptive_fake_ttl_selector_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            AdaptiveFakeTtlModeCard(
                preset = preset,
                selected = uiState.adaptiveFakeTtlMode == preset.value,
                enabled = enabled && preset.value != AdaptiveFakeTtlModeCustom,
                onClick = { onModeSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun AdaptiveFakeTtlModeCard(
    preset: AdaptiveFakeTtlModeUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    RipDpiCard(
        modifier = modifier,
        variant = if (selected) RipDpiCardVariant.Tonal else RipDpiCardVariant.Outlined,
        enabled = enabled,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = preset.title,
                    style = type.bodyEmphasis,
                    color = colors.foreground,
                )
                Text(
                    text = preset.body,
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
            }
            val badgeLabel =
                when {
                    selected -> stringResource(R.string.adaptive_fake_ttl_mode_selected)
                    preset.badgeLabel != null -> preset.badgeLabel
                    else -> null
                }
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = if (selected) StatusIndicatorTone.Active else preset.badgeTone,
                )
            }
        }
    }
}
