package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.isValidOffsetExpression
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.utility.validateIntRange

private const val tlsRandRecFragmentCountMin = 2
private const val tlsRandRecFragmentCountMax = 16
private const val tlsRandRecFragmentSizeMax = 4096

private val TlsPreludeAsciiKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Ascii,
        imeAction = ImeAction.Done,
    )

private val TlsPreludeNumericKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
    )

internal fun LazyListScope.httpsSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
) {
    item(key = "advanced_https") {
        AdvancedSettingsSection(
            title = stringResource(R.string.desync_https_category),
            testTag = RipDpiTestTags.advancedSection("https"),
        ) {
            HttpsSettingsCard(
                uiState = uiState,
                visualEditorEnabled = visualEditorEnabled,
                onTextConfirmed = onTextConfirmed,
                onOptionSelected = onOptionSelected,
            )
        }
    }
}

private data class TlsPreludeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class TlsPreludeSummaryLines(
    val mode: String,
    val marker: String,
    val layout: String,
    val scope: String,
)

private data class TlsPreludePresetUiModel(
    val value: String,
    val title: String,
    val body: String,
)

@Composable
private fun TlsPreludeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberTlsPreludeStatus(uiState)
    val summary = tlsPreludeSummaryLines(uiState)
    val badges = tlsPreludeBadges(uiState)

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Tonal,
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
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_mode),
                value = summary.mode,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_marker),
                value = summary.marker,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_layout),
                value = summary.layout,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.ripdpi_tls_prelude_summary_label_scope),
                value = summary.scope,
            )
        }
    }
}

@Composable
private fun HttpsSettingsCard(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard {
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_section_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = colors.mutedForeground,
        )
        HorizontalDivider(color = colors.divider)
        TlsPreludeProfileCard(
            uiState = uiState,
            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
        )
        HorizontalDivider(color = colors.divider)
        TlsPreludeModeSelector(
            uiState = uiState,
            enabled = visualEditorEnabled,
            onModeSelected = { onOptionSelected(AdvancedOptionSetting.TlsPreludeMode, it) },
        )
        TlsPreludeFields(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onTextConfirmed = onTextConfirmed,
        )
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_scope_note),
            style = RipDpiThemeTokens.type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun TlsPreludeFields(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    if (uiState.tlsPrelude.hasStackedTlsPreludeSteps) {
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_multiple_note),
            style = RipDpiThemeTokens.type.caption,
            color = colors.warning,
        )
    }
    if (uiState.tlsPrelude.tlsPreludeMode != TlsPreludeModeDisabled) {
        HorizontalDivider(color = colors.divider)
        TlsPreludeMarkerSetting(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onTextConfirmed = onTextConfirmed,
        )
    }
    if (uiState.tlsPrelude.tlsPreludeUsesRandomRecords) {
        TlsRandRecSettings(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onTextConfirmed = onTextConfirmed,
        )
    }
}

@Composable
private fun TlsPreludeMarkerSetting(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_tlsrec_position_setting),
        description = stringResource(R.string.config_tls_record_marker_helper),
        value = uiState.tlsPrelude.tlsrecMarker,
        placeholder = stringResource(R.string.config_placeholder_tls_record_marker),
        enabled = visualEditorEnabled,
        validator = ::isValidOffsetExpression,
        invalidMessage = stringResource(R.string.config_error_invalid_marker),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = TlsPreludeAsciiKeyboard,
        setting = AdvancedTextSetting.TlsrecMarker,
        onConfirm = onTextConfirmed,
        showDivider = uiState.tlsPrelude.tlsPreludeUsesRandomRecords,
    )
}

@Composable
private fun TlsRandRecSettings(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_tlsrandrec_count_title),
        description = stringResource(R.string.ripdpi_tlsrandrec_count_body),
        value = uiState.tlsPrelude.tlsRandRecFragmentCount.toString(),
        enabled = visualEditorEnabled,
        validator = { validateIntRange(it, tlsRandRecFragmentCountMin, tlsRandRecFragmentCountMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = TlsPreludeNumericKeyboard,
        setting = AdvancedTextSetting.TlsRandRecFragmentCount,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
    TlsRandRecSizeSettings(
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
}

@Composable
private fun TlsRandRecSizeSettings(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_tlsrandrec_min_title),
        description = stringResource(R.string.ripdpi_tlsrandrec_min_body),
        value = uiState.tlsPrelude.tlsRandRecMinFragmentSize.toString(),
        enabled = visualEditorEnabled,
        validator = { validateIntRange(it, 1, tlsRandRecFragmentSizeMax) },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = TlsPreludeNumericKeyboard,
        setting = AdvancedTextSetting.TlsRandRecMinFragmentSize,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.ripdpi_tlsrandrec_max_title),
        description = stringResource(R.string.ripdpi_tlsrandrec_max_body),
        value = uiState.tlsPrelude.tlsRandRecMaxFragmentSize.toString(),
        enabled = visualEditorEnabled,
        validator = { input -> tlsRandRecMaxSizeValid(input, uiState) },
        invalidMessage = stringResource(R.string.ripdpi_tlsrandrec_max_error),
        disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
        keyboardOptions = TlsPreludeNumericKeyboard,
        setting = AdvancedTextSetting.TlsRandRecMaxFragmentSize,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
}

private fun tlsRandRecMaxSizeValid(
    input: String,
    uiState: SettingsUiState,
): Boolean =
    input.toIntOrNull()?.let { value ->
        value in uiState.tlsPrelude.tlsRandRecMinFragmentSize..tlsRandRecFragmentSizeMax
    } == true

@Composable
private fun tlsPreludeSummaryLines(uiState: SettingsUiState): TlsPreludeSummaryLines =
    TlsPreludeSummaryLines(
        mode = tlsPreludeModeSummary(uiState),
        marker = tlsPreludeMarkerSummary(uiState),
        layout = tlsPreludeLayoutSummary(uiState),
        scope = tlsPreludeScopeSummary(uiState),
    )

@Composable
private fun tlsPreludeModeSummary(uiState: SettingsUiState): String =
    stringResource(
        when (uiState.tlsPrelude.tlsPreludeMode) {
            TcpChainStepKind.TlsRec.wireName -> R.string.ripdpi_tls_prelude_summary_mode_single
            TcpChainStepKind.TlsRandRec.wireName -> R.string.ripdpi_tls_prelude_summary_mode_random
            else -> R.string.ripdpi_tls_prelude_summary_mode_off
        },
    )

@Composable
private fun tlsPreludeMarkerSummary(uiState: SettingsUiState): String =
    if (uiState.tlsPrelude.tlsPreludeMode == TlsPreludeModeDisabled) {
        stringResource(R.string.ripdpi_tls_prelude_marker_unused)
    } else {
        uiState.tlsPrelude.tlsrecMarker
    }

@Composable
private fun tlsPreludeLayoutSummary(uiState: SettingsUiState): String =
    when (uiState.tlsPrelude.tlsPreludeMode) {
        TcpChainStepKind.TlsRandRec.wireName -> {
            stringResource(
                R.string.ripdpi_tls_prelude_summary_layout_random,
                uiState.tlsPrelude.tlsRandRecFragmentCount,
                uiState.tlsPrelude.tlsRandRecMinFragmentSize,
                uiState.tlsPrelude.tlsRandRecMaxFragmentSize,
            )
        }

        TcpChainStepKind.TlsRec.wireName -> {
            stringResource(R.string.ripdpi_tls_prelude_summary_layout_single)
        }

        else -> {
            stringResource(R.string.ripdpi_tls_prelude_summary_layout_off)
        }
    }

@Composable
private fun tlsPreludeScopeSummary(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.ripdpi_tls_prelude_scope_cli)
        !uiState.tlsPreludeControlsRelevant -> stringResource(R.string.ripdpi_tls_prelude_scope_https_disabled)
        uiState.tlsPrelude.hasStackedTlsPreludeSteps -> stringResource(R.string.ripdpi_tls_prelude_scope_stacked)
        else -> stringResource(R.string.ripdpi_tls_prelude_scope_active)
    }

@Composable
private fun tlsPreludeBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    buildList {
        add(stringResource(R.string.ripdpi_tls_prelude_badge_https_only) to SummaryCapsuleTone.Info)
        tlsPreludeModeBadge(uiState)?.let(::add)
        if (uiState.tlsPrelude.hasStackedTlsPreludeSteps) {
            add(
                stringResource(
                    R.string.ripdpi_tls_prelude_badge_stacked,
                    uiState.tlsPrelude.tlsPreludeStepCount,
                ) to SummaryCapsuleTone.Warning,
            )
        }
    }

@Composable
private fun tlsPreludeModeBadge(uiState: SettingsUiState): Pair<String, SummaryCapsuleTone>? =
    when (uiState.tlsPrelude.tlsPreludeMode) {
        TcpChainStepKind.TlsRec.wireName -> {
            stringResource(R.string.ripdpi_tls_prelude_badge_single) to SummaryCapsuleTone.Active
        }

        TcpChainStepKind.TlsRandRec.wireName -> {
            stringResource(
                R.string.ripdpi_tls_prelude_badge_random,
                uiState.tlsPrelude.tlsRandRecFragmentCount,
            ) to SummaryCapsuleTone.Active
        }

        else -> {
            null
        }
    }

@Composable
private fun rememberTlsPreludeStatus(uiState: SettingsUiState): TlsPreludeStatusContent =
    when {
        uiState.enableCmdSettings -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_cli_title),
                body = stringResource(R.string.ripdpi_tls_prelude_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.tlsPreludeControlsRelevant && uiState.tlsPrelude.tlsPreludeStepCount > 0 -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_https_disabled_title),
                body = stringResource(R.string.ripdpi_tls_prelude_https_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.tlsPrelude.hasStackedTlsPreludeSteps -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_stacked_title),
                body = stringResource(R.string.ripdpi_tls_prelude_stacked_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.tlsPrelude.tlsPreludeMode == TlsPreludeModeDisabled -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_off_title),
                body = stringResource(R.string.ripdpi_tls_prelude_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        uiState.isServiceRunning -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_restart_title),
                body = stringResource(R.string.ripdpi_tls_prelude_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.tlsPrelude.tlsPreludeUsesRandomRecords -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_random_title),
                body = stringResource(R.string.ripdpi_tls_prelude_random_body),
                tone = StatusIndicatorTone.Active,
            )
        }

        else -> {
            TlsPreludeStatusContent(
                label = stringResource(R.string.ripdpi_tls_prelude_single_title),
                body = stringResource(R.string.ripdpi_tls_prelude_single_body),
                tone = StatusIndicatorTone.Active,
            )
        }
    }

@Composable
private fun TlsPreludeModeSelector(
    uiState: SettingsUiState,
    enabled: Boolean,
    onModeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val presets =
        listOf(
            TlsPreludePresetUiModel(
                value = TlsPreludeModeDisabled,
                title = stringResource(R.string.ripdpi_tls_prelude_mode_off_title),
                body = stringResource(R.string.ripdpi_tls_prelude_mode_off_body),
            ),
            TlsPreludePresetUiModel(
                value = TcpChainStepKind.TlsRec.wireName,
                title = stringResource(R.string.ripdpi_tls_prelude_mode_single_title),
                body = stringResource(R.string.ripdpi_tls_prelude_mode_single_body),
            ),
            TlsPreludePresetUiModel(
                value = TcpChainStepKind.TlsRandRec.wireName,
                title = stringResource(R.string.ripdpi_tls_prelude_mode_random_title),
                body = stringResource(R.string.ripdpi_tls_prelude_mode_random_body),
            ),
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_mode_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.ripdpi_tls_prelude_mode_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            TlsPreludePresetCard(
                preset = preset,
                selected = uiState.tlsPrelude.tlsPreludeMode == preset.value,
                enabled = enabled,
                onClick = { onModeSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun TlsPreludePresetCard(
    preset: TlsPreludePresetUiModel,
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
            if (selected) {
                StatusIndicator(
                    label = stringResource(R.string.quic_fake_preset_selected),
                    tone = StatusIndicatorTone.Active,
                )
            }
        }
    }
}
