package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange

private const val cacheTtlMaxSeconds = 3600
private const val cachePrefixMaxV4 = 32

internal fun LazyListScope.adaptiveFallbackSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_adaptive_fallback") {
        val spacing = RipDpiThemeTokens.spacing
        AdvancedSettingsSection(
            title = stringResource(R.string.adaptive_fallback_section_title),
            testTag = RipDpiTestTags.advancedSection("adaptive_fallback"),
        ) {
            AdaptiveFallbackSummaryCard(
                uiState = uiState,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            RipDpiCard(variant = RipDpiCardVariant.Outlined) {
                SettingsRow(
                    title = stringResource(R.string.adaptive_fallback_enabled_title),
                    subtitle = stringResource(R.string.adaptive_fallback_enabled_body),
                    checked = uiState.adaptiveFallback.enabled,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AdaptiveFallbackEnabled, it) },
                    enabled = visualEditorEnabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AdaptiveFallbackEnabled),
                )
                SettingsRow(
                    title = stringResource(R.string.adaptive_fallback_torst_title),
                    checked = uiState.adaptiveFallback.torst,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AdaptiveFallbackTorst, it) },
                    enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AdaptiveFallbackTorst),
                )
                SettingsRow(
                    title = stringResource(R.string.adaptive_fallback_tls_err_title),
                    checked = uiState.adaptiveFallback.tlsErr,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AdaptiveFallbackTlsErr, it) },
                    enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AdaptiveFallbackTlsErr),
                )
                SettingsRow(
                    title = stringResource(R.string.adaptive_fallback_http_redirect_title),
                    checked = uiState.adaptiveFallback.httpRedirect,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AdaptiveFallbackHttpRedirect, it) },
                    enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AdaptiveFallbackHttpRedirect),
                )
                SettingsRow(
                    title = stringResource(R.string.adaptive_fallback_connect_failure_title),
                    checked = uiState.adaptiveFallback.connectFailure,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AdaptiveFallbackConnectFailure, it) },
                    enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AdaptiveFallbackConnectFailure),
                )
                SettingsRow(
                    title = stringResource(R.string.adaptive_fallback_auto_sort_title),
                    subtitle = stringResource(R.string.adaptive_fallback_auto_sort_body),
                    checked = uiState.adaptiveFallback.autoSort,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.AdaptiveFallbackAutoSort, it) },
                    enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.AdaptiveFallbackAutoSort),
                )
                AdaptiveFallbackNumericFields(
                    uiState = uiState,
                    visualEditorEnabled = visualEditorEnabled,
                    onTextConfirmed = onTextConfirmed,
                )
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun AdaptiveFallbackSummaryCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val status =
        when {
            uiState.enableCmdSettings -> {
                Triple(
                    stringResource(R.string.adaptive_fallback_cli_title),
                    stringResource(R.string.adaptive_fallback_cli_body),
                    StatusIndicatorTone.Warning,
                )
            }

            !uiState.adaptiveFallback.enabled -> {
                Triple(
                    stringResource(R.string.adaptive_fallback_disabled_title),
                    stringResource(R.string.adaptive_fallback_disabled_body),
                    StatusIndicatorTone.Idle,
                )
            }

            !uiState.adaptiveFallback.hasAnyTrigger -> {
                Triple(
                    stringResource(R.string.adaptive_fallback_no_triggers_title),
                    stringResource(R.string.adaptive_fallback_no_triggers_body),
                    StatusIndicatorTone.Warning,
                )
            }

            uiState.isServiceRunning -> {
                Triple(
                    stringResource(R.string.adaptive_fallback_restart_title),
                    stringResource(R.string.adaptive_fallback_restart_body),
                    StatusIndicatorTone.Warning,
                )
            }

            else -> {
                Triple(
                    stringResource(R.string.adaptive_fallback_ready_title),
                    stringResource(R.string.adaptive_fallback_ready_body),
                    StatusIndicatorTone.Active,
                )
            }
        }
    val badges =
        buildList {
            add(
                (
                    if (uiState.adaptiveFallback.enabled) {
                        stringResource(R.string.adaptive_fallback_badge_enabled)
                    } else {
                        stringResource(R.string.adaptive_fallback_badge_disabled)
                    }
                ) to if (uiState.adaptiveFallback.enabled) SummaryCapsuleTone.Active else SummaryCapsuleTone.Neutral,
            )
            add(
                stringResource(
                    R.string.adaptive_fallback_badge_triggers,
                    uiState.adaptiveFallback.triggerCount,
                ) to SummaryCapsuleTone.Info,
            )
            if (uiState.adaptiveFallback.autoSort) {
                add(stringResource(R.string.adaptive_fallback_badge_auto_sort) to SummaryCapsuleTone.Active)
            }
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Elevated,
    ) {
        StatusIndicator(label = status.first, tone = status.third)
        Text(
            text = status.second,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fallback_summary_label_scope),
                value =
                    if (uiState.enableCmdSettings) {
                        stringResource(R.string.adaptive_fallback_scope_cli)
                    } else if (uiState.adaptiveFallbackControlsRelevant) {
                        stringResource(R.string.adaptive_fallback_scope_active)
                    } else {
                        stringResource(R.string.adaptive_fallback_scope_idle)
                    },
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fallback_summary_label_triggers),
                value = adaptiveFallbackTriggerSummary(uiState),
            )
            ProfileSummaryLine(
                label = stringResource(R.string.adaptive_fallback_summary_label_cache),
                value =
                    stringResource(
                        R.string.adaptive_fallback_cache_summary,
                        uiState.adaptiveFallback.cacheTtlSeconds,
                        uiState.adaptiveFallback.cachePrefixV4,
                    ),
            )
            uiState.adaptiveFallback.runtimeOverrideSummary?.let { summary ->
                ProfileSummaryLine(
                    label = stringResource(R.string.adaptive_fallback_summary_label_runtime_override),
                    value =
                        if (uiState.adaptiveFallback.runtimeOverrideRememberedPolicy) {
                            stringResource(R.string.adaptive_fallback_runtime_override_remembered, summary)
                        } else {
                            summary
                        },
                )
            }
        }
        Text(
            text = stringResource(R.string.adaptive_fallback_section_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun AdaptiveFallbackNumericFields(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    val numericKeyboard =
        KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        )
    AdvancedTextSetting(
        title = stringResource(R.string.adaptive_fallback_cache_ttl_title),
        description = stringResource(R.string.adaptive_fallback_cache_ttl_body),
        value = uiState.adaptiveFallback.cacheTtlSeconds.toString(),
        enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
        validator = { validateIntRange(it, 1, cacheTtlMaxSeconds) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = numericKeyboard,
        setting = AdvancedTextSetting.AdaptiveFallbackCacheTtlSeconds,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.adaptive_fallback_cache_prefix_title),
        description = stringResource(R.string.adaptive_fallback_cache_prefix_body),
        value = uiState.adaptiveFallback.cachePrefixV4.toString(),
        enabled = visualEditorEnabled && uiState.adaptiveFallback.enabled,
        validator = { validateIntRange(it, 1, cachePrefixMaxV4) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = numericKeyboard,
        setting = AdvancedTextSetting.AdaptiveFallbackCachePrefixV4,
        onConfirm = onTextConfirmed,
    )
}

@Composable
private fun adaptiveFallbackTriggerSummary(uiState: SettingsUiState): String =
    buildList {
        if (uiState.adaptiveFallback.torst) {
            add(stringResource(R.string.adaptive_fallback_torst_title))
        }
        if (uiState.adaptiveFallback.tlsErr) {
            add(stringResource(R.string.adaptive_fallback_tls_err_title))
        }
        if (uiState.adaptiveFallback.httpRedirect) {
            add(stringResource(R.string.adaptive_fallback_http_redirect_title))
        }
        if (uiState.adaptiveFallback.connectFailure) {
            add(stringResource(R.string.adaptive_fallback_connect_failure_title))
        }
    }.joinToString(separator = " · ")
        .ifBlank { stringResource(R.string.adaptive_fallback_no_triggers_summary) }
