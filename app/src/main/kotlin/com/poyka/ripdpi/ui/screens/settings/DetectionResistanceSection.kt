package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.EntropyModeDisabled
import com.poyka.ripdpi.data.tlsFingerprintProfileSummary
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.indicators.StatusIndicator
import com.poyka.ripdpi.ui.components.indicators.StatusIndicatorTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private data class DetectionResistanceSummaryLines(
    val tls: String,
    val entropy: String,
    val exploration: String,
    val quic: String,
)

internal fun LazyListScope.detectionResistanceSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    tlsFingerprintOptions: ImmutableList<RipDpiDropdownOption<String>>,
    entropyModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_detection_resistance") {
        val spacing = RipDpiThemeTokens.spacing
        AdvancedSettingsSection(
            title = stringResource(R.string.detection_resistance_section_title),
            testTag = RipDpiTestTags.advancedSection("detection_resistance"),
        ) {
            DetectionResistanceSummaryCard(
                uiState = uiState,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            if (!uiState.isVpn) {
                DetectionResistanceProxyModeNotice(
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
            }
            DetectionResistanceControlsCard(
                uiState = uiState,
                visualEditorEnabled = visualEditorEnabled,
                tlsFingerprintOptions = tlsFingerprintOptions,
                entropyModeOptions = entropyModeOptions,
                onToggleChanged = onToggleChanged,
                onOptionSelected = onOptionSelected,
                onTextConfirmed = onTextConfirmed,
            )
        }
    }
}

private val NumericKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
    )

private val DecimalKeyboard =
    KeyboardOptions(
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )

@Composable
private fun DetectionResistanceSummaryCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val resistance = uiState.detectionResistance
    val tone = detectionResistanceTone(uiState)
    val label = detectionResistanceLabel(uiState)
    val badges = detectionResistanceBadges(uiState)
    val summary = detectionResistanceSummaryLines(uiState)

    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Elevated) {
        StatusIndicator(label = label, tone = tone)
        Text(
            text = stringResource(R.string.detection_resistance_section_body),
            style = type.secondaryBody,
            color = colors.foreground,
        )
        SummaryCapsuleFlow(items = badges)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_tls),
                value = summary.tls,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_entropy),
                value = summary.entropy,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_exploration),
                value = summary.exploration,
            )
            ProfileSummaryLine(
                label = stringResource(R.string.detection_resistance_summary_quic),
                value = summary.quic,
            )
        }
        Text(
            text = stringResource(R.string.detection_resistance_restart_body),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun DetectionResistanceControlsCard(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    tlsFingerprintOptions: ImmutableList<RipDpiDropdownOption<String>>,
    entropyModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        DetectionResistanceProfileControls(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            tlsFingerprintOptions = tlsFingerprintOptions,
            entropyModeOptions = entropyModeOptions,
            onOptionSelected = onOptionSelected,
            onTextConfirmed = onTextConfirmed,
        )
        HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
        DetectionResistanceEvolutionControls(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
            onTextConfirmed = onTextConfirmed,
        )
        HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
        DetectionResistanceQuicControls(
            uiState = uiState,
            visualEditorEnabled = visualEditorEnabled,
            onToggleChanged = onToggleChanged,
        )
    }
}

@Composable
private fun detectionResistanceTone(uiState: SettingsUiState): StatusIndicatorTone =
    when {
        uiState.enableCmdSettings -> StatusIndicatorTone.Warning
        detectionResistanceHasActiveFeature(uiState) -> StatusIndicatorTone.Active
        else -> StatusIndicatorTone.Idle
    }

@Composable
private fun detectionResistanceLabel(uiState: SettingsUiState): String =
    when {
        uiState.enableCmdSettings -> stringResource(R.string.detection_resistance_cli_title)
        detectionResistanceHasActiveFeature(uiState) -> stringResource(R.string.detection_resistance_active_title)
        else -> stringResource(R.string.detection_resistance_idle_title)
    }

private fun detectionResistanceHasActiveFeature(uiState: SettingsUiState): Boolean =
    uiState.detectionResistance.tlsFingerprintProfileActive ||
        uiState.detectionResistance.entropyEnabled ||
        uiState.detectionResistance.strategyEvolution ||
        uiState.detectionResistance.quicMigrateAfterHandshake

@Composable
private fun detectionResistanceBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    buildList {
        val resistance = uiState.detectionResistance
        if (resistance.tlsFingerprintProfileActive) {
            add(tlsFingerprintProfileSummary(resistance.tlsFingerprintProfile) to SummaryCapsuleTone.Active)
        }
        if (resistance.entropyEnabled) {
            add(resistance.entropyMode to SummaryCapsuleTone.Active)
        }
        if (resistance.strategyEvolution) {
            add(stringResource(R.string.detection_resistance_badge_evolution) to SummaryCapsuleTone.Info)
        }
        if (resistance.quicBindLowPort) {
            add(stringResource(R.string.detection_resistance_badge_low_port) to SummaryCapsuleTone.Info)
        }
        if (resistance.quicMigrateAfterHandshake) {
            add(stringResource(R.string.detection_resistance_badge_quic_migrate) to SummaryCapsuleTone.Active)
        }
        if (isEmpty()) {
            add(stringResource(R.string.detection_resistance_badge_default) to SummaryCapsuleTone.Neutral)
        }
    }

@Composable
private fun detectionResistanceSummaryLines(uiState: SettingsUiState): DetectionResistanceSummaryLines =
    DetectionResistanceSummaryLines(
        tls = tlsFingerprintProfileSummary(uiState.detectionResistance.tlsFingerprintProfile),
        entropy = detectionResistanceEntropySummary(uiState),
        exploration = detectionResistanceExplorationSummary(uiState),
        quic = detectionResistanceQuicSummary(uiState),
    )

@Composable
private fun detectionResistanceEntropySummary(uiState: SettingsUiState): String =
    if (uiState.detectionResistance.entropyEnabled) {
        stringResource(
            R.string.detection_resistance_summary_entropy_value,
            uiState.detectionResistance.entropyPaddingTargetPermil,
            uiState.detectionResistance.shannonEntropyTargetPermil,
        )
    } else {
        stringResource(R.string.detection_resistance_summary_off)
    }

@Composable
private fun detectionResistanceExplorationSummary(uiState: SettingsUiState): String =
    if (uiState.detectionResistance.strategyEvolution) {
        stringResource(
            R.string.detection_resistance_summary_epsilon_value,
            uiState.detectionResistance.evolutionEpsilon,
        )
    } else {
        stringResource(R.string.detection_resistance_summary_off)
    }

@Composable
private fun detectionResistanceQuicSummary(uiState: SettingsUiState): String =
    when {
        uiState.detectionResistance.quicMigrateAfterHandshake -> {
            stringResource(R.string.detection_resistance_summary_quic_migrate)
        }

        uiState.detectionResistance.quicBindLowPort -> {
            stringResource(R.string.detection_resistance_summary_quic_low_port)
        }

        else -> {
            stringResource(R.string.detection_resistance_summary_off)
        }
    }

@Composable
private fun DetectionResistanceProfileControls(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    tlsFingerprintOptions: ImmutableList<RipDpiDropdownOption<String>>,
    entropyModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    AdvancedDropdownSetting(
        title = stringResource(R.string.detection_resistance_tls_fingerprint_title),
        description = stringResource(R.string.detection_resistance_tls_fingerprint_body),
        value = uiState.detectionResistance.tlsFingerprintProfile,
        enabled = visualEditorEnabled,
        options = tlsFingerprintOptions,
        setting = AdvancedOptionSetting.TlsFingerprintProfile,
        onSelected = onOptionSelected,
        showDivider = true,
    )
    AdvancedDropdownSetting(
        title = stringResource(R.string.detection_resistance_entropy_mode_title),
        description = stringResource(R.string.detection_resistance_entropy_mode_body),
        value = uiState.detectionResistance.entropyMode,
        enabled = visualEditorEnabled,
        options = entropyModeOptions,
        setting = AdvancedOptionSetting.EntropyMode,
        onSelected = onOptionSelected,
        showDivider = true,
    )
    DetectionResistanceMorphingFields(
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
}

@Composable
private fun DetectionResistanceEvolutionControls(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.detection_resistance_strategy_evolution_title),
        subtitle = stringResource(R.string.detection_resistance_strategy_evolution_body),
        checked = uiState.detectionResistance.strategyEvolution,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.StrategyEvolution, it) },
        enabled = visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.StrategyEvolution),
    )
    DetectionResistanceEvolutionFields(
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
}

@Composable
private fun DetectionResistanceEvolutionFields(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    DetectionResistanceEvolutionTextSetting(
        title = stringResource(R.string.detection_resistance_evolution_epsilon_title),
        value = uiState.detectionResistance.evolutionEpsilon.toString(),
        setting = AdvancedTextSetting.EvolutionEpsilon,
        description = stringResource(R.string.detection_resistance_evolution_epsilon_body),
        keyboardOptions = DecimalKeyboard,
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
    DetectionResistanceEvolutionTextSetting(
        title = stringResource(R.string.detection_resistance_evolution_experiment_ttl_title),
        value = uiState.detectionResistance.evolutionExperimentTtlMs.toString(),
        setting = AdvancedTextSetting.EvolutionExperimentTtlMs,
        description = stringResource(R.string.detection_resistance_evolution_experiment_ttl_body),
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
    DetectionResistanceEvolutionTextSetting(
        title = stringResource(R.string.detection_resistance_evolution_decay_half_life_title),
        value = uiState.detectionResistance.evolutionDecayHalfLifeMs.toString(),
        setting = AdvancedTextSetting.EvolutionDecayHalfLifeMs,
        description = stringResource(R.string.detection_resistance_evolution_decay_half_life_body),
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
    DetectionResistanceEvolutionTextSetting(
        title = stringResource(R.string.detection_resistance_evolution_cooldown_failures_title),
        value = uiState.detectionResistance.evolutionCooldownAfterFailures.toString(),
        setting = AdvancedTextSetting.EvolutionCooldownAfterFailures,
        description = stringResource(R.string.detection_resistance_evolution_cooldown_failures_body),
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
    DetectionResistanceEvolutionTextSetting(
        title = stringResource(R.string.detection_resistance_evolution_cooldown_ms_title),
        value = uiState.detectionResistance.evolutionCooldownMs.toString(),
        setting = AdvancedTextSetting.EvolutionCooldownMs,
        description = stringResource(R.string.detection_resistance_evolution_cooldown_ms_body),
        uiState = uiState,
        visualEditorEnabled = visualEditorEnabled,
        onTextConfirmed = onTextConfirmed,
    )
}

@Composable
private fun DetectionResistanceEvolutionTextSetting(
    title: String,
    value: String,
    setting: AdvancedTextSetting,
    description: String,
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    keyboardOptions: KeyboardOptions = NumericKeyboard,
) {
    AdvancedTextSetting(
        title = title,
        value = value,
        setting = setting,
        onConfirm = onTextConfirmed,
        description = description,
        enabled = visualEditorEnabled && uiState.detectionResistance.strategyEvolution,
        keyboardOptions = keyboardOptions,
    )
}

@Composable
private fun DetectionResistanceQuicControls(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.detection_resistance_quic_low_port_title),
        subtitle = stringResource(R.string.detection_resistance_quic_low_port_body),
        checked = uiState.detectionResistance.quicBindLowPort,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicBindLowPort, it) },
        enabled = visualEditorEnabled,
        showDivider = true,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.QuicBindLowPort),
    )
    SettingsRow(
        title = stringResource(R.string.detection_resistance_quic_migrate_title),
        subtitle = detectionResistanceQuicMigrateBody(uiState),
        checked = uiState.detectionResistance.quicMigrateAfterHandshake,
        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicMigrateAfterHandshake, it) },
        enabled = visualEditorEnabled && uiState.isVpn,
        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.QuicMigrateAfterHandshake),
    )
}

@Composable
private fun detectionResistanceQuicMigrateBody(uiState: SettingsUiState): String =
    if (uiState.isVpn) {
        stringResource(R.string.detection_resistance_quic_migrate_body)
    } else {
        stringResource(R.string.detection_resistance_quic_migrate_vpn_only)
    }

@Composable
private fun DetectionResistanceProxyModeNotice(modifier: Modifier = Modifier) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing

    RipDpiCard(modifier = modifier, variant = RipDpiCardVariant.Outlined) {
        StatusIndicator(
            label = stringResource(R.string.detection_resistance_proxy_notice_title),
            tone = StatusIndicatorTone.Warning,
        )
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.detection_resistance_proxy_notice_body),
                style = type.secondaryBody,
                color = colors.foreground,
            )
            Text(
                text = stringResource(R.string.detection_resistance_quic_migrate_vpn_only),
                style = type.caption,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun DetectionResistanceMorphingFields(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    val enabled = visualEditorEnabled && uiState.detectionResistance.entropyMode != EntropyModeDisabled
    AdvancedTextSetting(
        title = stringResource(R.string.detection_resistance_entropy_padding_title),
        value = uiState.detectionResistance.entropyPaddingTargetPermil.toString(),
        setting = AdvancedTextSetting.EntropyPaddingTargetPermil,
        onConfirm = onTextConfirmed,
        description = stringResource(R.string.detection_resistance_entropy_padding_body),
        enabled = enabled,
        keyboardOptions = NumericKeyboard,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.detection_resistance_entropy_padding_max_title),
        value = uiState.detectionResistance.entropyPaddingMax.toString(),
        setting = AdvancedTextSetting.EntropyPaddingMax,
        onConfirm = onTextConfirmed,
        description = stringResource(R.string.detection_resistance_entropy_padding_max_body),
        enabled = enabled,
        keyboardOptions = NumericKeyboard,
        showDivider = true,
    )
    AdvancedTextSetting(
        title = stringResource(R.string.detection_resistance_shannon_title),
        value = uiState.detectionResistance.shannonEntropyTargetPermil.toString(),
        setting = AdvancedTextSetting.ShannonEntropyTargetPermil,
        onConfirm = onTextConfirmed,
        description = stringResource(R.string.detection_resistance_shannon_body),
        enabled = enabled,
        keyboardOptions = NumericKeyboard,
    )
}
