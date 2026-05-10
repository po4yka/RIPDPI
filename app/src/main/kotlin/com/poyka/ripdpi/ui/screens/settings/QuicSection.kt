package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DefaultQuicFakeHost
import com.poyka.ripdpi.data.QuicFakeProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicFakeProfileRealisticInitial
import com.poyka.ripdpi.data.QuicInitialModeDisabled
import com.poyka.ripdpi.data.normalizeQuicFakeHost
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

internal fun LazyListScope.udpSection() {
    item(key = "advanced_udp") {
        AdvancedSettingsSection(
            title = stringResource(R.string.desync_udp_category),
            testTag = RipDpiTestTags.advancedSection("udp"),
        ) {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.config_udp_chain_hint),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = RipDpiThemeTokens.colors.mutedForeground,
                )
            }
        }
    }
}

internal fun LazyListScope.quicSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    showQuicFakeSection: Boolean,
    quicModeOptions: ImmutableList<RipDpiDropdownOption<String>>,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_quic") {
        val colors = RipDpiThemeTokens.colors

        AdvancedSettingsSection(
            title = stringResource(R.string.quic_initial_section_title),
            testTag = RipDpiTestTags.advancedSection("quic"),
        ) {
            RipDpiCard {
                Text(
                    text = stringResource(R.string.quic_initial_section_body),
                    style = RipDpiThemeTokens.type.secondaryBody,
                    color = colors.mutedForeground,
                )
                HorizontalDivider(color = colors.divider)
                AdvancedDropdownSetting(
                    title = stringResource(R.string.quic_initial_mode_title),
                    description = stringResource(R.string.quic_initial_mode_body),
                    value = uiState.quic.quicInitialMode,
                    enabled = visualEditorEnabled,
                    options = quicModeOptions,
                    setting = AdvancedOptionSetting.QuicInitialMode,
                    onSelected = onOptionSelected,
                    showDivider = uiState.quic.quicInitialMode != QuicInitialModeDisabled,
                )
                if (uiState.quic.quicInitialMode != QuicInitialModeDisabled) {
                    SettingsRow(
                        title = stringResource(R.string.quic_initial_support_v1_title),
                        subtitle = stringResource(R.string.quic_initial_support_v1_body),
                        checked = uiState.quic.quicSupportV1,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicSupportV1, it) },
                        enabled = visualEditorEnabled,
                        showDivider = true,
                        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.QuicSupportV1),
                    )
                    SettingsRow(
                        title = stringResource(R.string.quic_initial_support_v2_title),
                        subtitle = stringResource(R.string.quic_initial_support_v2_body),
                        checked = uiState.quic.quicSupportV2,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.QuicSupportV2, it) },
                        enabled = visualEditorEnabled,
                        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.QuicSupportV2),
                    )
                }
                if (showQuicFakeSection) {
                    HorizontalDivider(color = colors.divider)
                    QuicFakeProfileCard(uiState = uiState)
                    QuicFakePresetSelector(
                        uiState = uiState,
                        enabled = visualEditorEnabled,
                        onProfileSelected = {
                            onOptionSelected(AdvancedOptionSetting.QuicFakeProfile, it)
                        },
                    )
                    if (uiState.quic.showQuicFakeHostOverride) {
                        QuicFakeHostOverrideCard(
                            uiState = uiState,
                            enabled = visualEditorEnabled,
                            onConfirm = onTextConfirmed,
                        )
                    }
                    Text(
                        text = stringResource(R.string.quic_fake_profile_helper),
                        style = RipDpiThemeTokens.type.caption,
                        color = colors.mutedForeground,
                    )
                }
            }
        }
    }
}

private data class QuicFakeStatusContent(
    val label: String,
    val body: String,
    val tone: StatusIndicatorTone,
)

private data class QuicFakePresetUiModel(
    val value: String,
    val title: String,
    val body: String,
    val isRecommended: Boolean = false,
)

@Composable
private fun QuicFakeProfileCard(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val status = rememberQuicFakeStatus(uiState)

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
        SummaryCapsuleFlow(items = quicFakeProfileBadges(uiState))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            QuicFakeProfileSummaryLines(uiState)
        }
    }
}

@Composable
private fun QuicFakeProfileSummaryLines(uiState: SettingsUiState) {
    ProfileSummaryLine(
        label = stringResource(R.string.quic_fake_profile_summary_label_preset),
        value = quicFakePresetSummary(uiState),
    )
    ProfileSummaryLine(
        label = stringResource(R.string.quic_fake_profile_summary_label_host),
        value = quicFakeHostSummary(uiState),
    )
    ProfileSummaryLine(
        label = stringResource(R.string.quic_fake_profile_summary_label_scope),
        value = quicFakeScopeSummary(uiState),
    )
    ProfileSummaryLine(
        label = stringResource(R.string.quic_fake_profile_summary_label_burst),
        value = quicFakeBurstSummary(uiState),
    )
}

@Composable
private fun quicFakePresetSummary(uiState: SettingsUiState): String =
    stringResource(
        when (uiState.quic.quicFakeProfile) {
            QuicFakeProfileCompatDefault -> R.string.quic_fake_profile_summary_compat
            QuicFakeProfileRealisticInitial -> R.string.quic_fake_profile_summary_realistic
            else -> R.string.quic_fake_profile_summary_off
        },
    )

@Composable
private fun quicFakeHostSummary(uiState: SettingsUiState): String =
    when (uiState.quic.quicFakeProfile) {
        QuicFakeProfileRealisticInitial -> uiState.quic.quicFakeHost.ifBlank { DefaultQuicFakeHost }
        else -> stringResource(R.string.quic_fake_profile_host_unused)
    }

@Composable
private fun quicFakeScopeSummary(uiState: SettingsUiState): String =
    when {
        !uiState.desyncUdpEnabled -> stringResource(R.string.quic_fake_profile_scope_udp_disabled)
        !uiState.desync.hasUdpFakeBurst -> stringResource(R.string.quic_fake_profile_scope_needs_burst)
        else -> stringResource(R.string.quic_fake_profile_scope_active)
    }

@Composable
private fun quicFakeBurstSummary(uiState: SettingsUiState): String =
    if (uiState.desync.hasUdpFakeBurst) {
        stringResource(R.string.quic_fake_profile_burst_configured, uiState.desync.udpFakeCount)
    } else {
        stringResource(R.string.quic_fake_profile_burst_missing)
    }

@Composable
private fun quicFakeProfileBadges(uiState: SettingsUiState): List<Pair<String, SummaryCapsuleTone>> =
    buildList {
        add(stringResource(R.string.quic_fake_profile_badge_initial_only) to SummaryCapsuleTone.Info)
        add(quicFakeBurstBadge(uiState))
        quicFakeProfileBadge(uiState)?.let(::add)
    }

@Composable
private fun quicFakeBurstBadge(uiState: SettingsUiState): Pair<String, SummaryCapsuleTone> =
    if (uiState.desync.hasUdpFakeBurst) {
        stringResource(R.string.quic_fake_profile_badge_burst_ready, uiState.desync.udpFakeCount) to
            SummaryCapsuleTone.Active
    } else {
        stringResource(R.string.quic_fake_profile_badge_burst_missing) to SummaryCapsuleTone.Warning
    }

@Composable
private fun quicFakeProfileBadge(uiState: SettingsUiState): Pair<String, SummaryCapsuleTone>? =
    when (uiState.quic.quicFakeProfile) {
        QuicFakeProfileCompatDefault -> {
            stringResource(R.string.quic_fake_profile_badge_compat_blob) to SummaryCapsuleTone.Neutral
        }

        QuicFakeProfileRealisticInitial -> {
            quicRealisticFakeHostBadge(uiState)
        }

        else -> {
            null
        }
    }

@Composable
private fun quicRealisticFakeHostBadge(uiState: SettingsUiState): Pair<String, SummaryCapsuleTone> =
    if (uiState.quic.quicFakeUsesCustomHost) {
        stringResource(R.string.quic_fake_profile_badge_host_custom)
    } else {
        stringResource(R.string.quic_fake_profile_badge_host_builtin)
    } to SummaryCapsuleTone.Active

@Composable
private fun QuicFakePresetSelector(
    uiState: SettingsUiState,
    enabled: Boolean,
    onProfileSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val presets =
        listOf(
            QuicFakePresetUiModel(
                value = QuicFakeProfileDisabled,
                title = stringResource(R.string.quic_fake_preset_off_title),
                body = stringResource(R.string.quic_fake_preset_off_body),
            ),
            QuicFakePresetUiModel(
                value = QuicFakeProfileCompatDefault,
                title = stringResource(R.string.quic_fake_preset_compat_title),
                body = stringResource(R.string.quic_fake_preset_compat_body),
            ),
            QuicFakePresetUiModel(
                value = QuicFakeProfileRealisticInitial,
                title = stringResource(R.string.quic_fake_preset_realistic_title),
                body = stringResource(R.string.quic_fake_preset_realistic_body),
                isRecommended = true,
            ),
        )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.quic_fake_profile_title),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = stringResource(R.string.quic_fake_profile_body),
            style = RipDpiThemeTokens.type.secondaryBody,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
        presets.forEach { preset ->
            QuicFakePresetCard(
                preset = preset,
                selected = uiState.quic.quicFakeProfile == preset.value,
                enabled = enabled,
                onClick = { onProfileSelected(preset.value) },
            )
        }
    }
}

@Composable
private fun QuicFakePresetCard(
    preset: QuicFakePresetUiModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val spacing = RipDpiThemeTokens.spacing
    val badgeLabel =
        when {
            selected -> stringResource(R.string.quic_fake_preset_selected)
            preset.isRecommended -> stringResource(R.string.quic_fake_preset_recommended)
            else -> null
        }
    val badgeTone =
        when {
            selected -> StatusIndicatorTone.Active
            preset.isRecommended -> StatusIndicatorTone.Idle
            else -> StatusIndicatorTone.Idle
        }

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
            badgeLabel?.let {
                StatusIndicator(
                    label = it,
                    tone = badgeTone,
                )
            }
        }
    }
}

@Composable
private fun QuicFakeHostOverrideCard(
    uiState: SettingsUiState,
    enabled: Boolean,
    onConfirm: (AdvancedTextSetting, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val type = RipDpiThemeTokens.type
    val statusLabel =
        if (uiState.quic.quicFakeUsesCustomHost) {
            stringResource(R.string.quic_fake_host_custom_title)
        } else {
            stringResource(R.string.quic_fake_host_builtin_title)
        }
    val statusBody =
        if (uiState.quic.quicFakeUsesCustomHost) {
            stringResource(R.string.quic_fake_host_custom_body)
        } else {
            stringResource(R.string.quic_fake_host_builtin_body)
        }

    RipDpiCard(
        modifier = modifier,
        variant = RipDpiCardVariant.Outlined,
    ) {
        StatusIndicator(
            label = statusLabel,
            tone = if (uiState.quic.quicFakeUsesCustomHost) StatusIndicatorTone.Active else StatusIndicatorTone.Idle,
        )
        Text(
            text = statusBody,
            style = type.secondaryBody,
            color = colors.foreground,
        )
        AdvancedTextSetting(
            title = stringResource(R.string.quic_fake_host_title),
            description = stringResource(R.string.quic_fake_host_body),
            value = uiState.quic.quicFakeHost,
            enabled = enabled,
            validator = { input ->
                input.isBlank() || normalizeQuicFakeHost(input).isNotEmpty()
            },
            invalidMessage = stringResource(R.string.quic_fake_host_error),
            disabledMessage = stringResource(R.string.advanced_settings_visual_controls_disabled),
            placeholder = stringResource(R.string.config_placeholder_quic_fake_host),
            setting = AdvancedTextSetting.QuicFakeHost,
            onConfirm = onConfirm,
        )
        ProfileSummaryLine(
            label = stringResource(R.string.quic_fake_host_effective_label),
            value = uiState.quic.quicFakeEffectiveHost,
        )
    }
}

@Composable
private fun rememberQuicFakeStatus(uiState: SettingsUiState): QuicFakeStatusContent =
    when {
        uiState.enableCmdSettings -> {
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_cli_title),
                body = stringResource(R.string.quic_fake_profile_cli_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        !uiState.quicFakeControlsRelevant -> {
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_udp_disabled_title),
                body = stringResource(R.string.quic_fake_profile_udp_disabled_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.quic.quicFakeProfileActive -> {
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_off_title),
                body = stringResource(R.string.quic_fake_profile_off_body),
                tone = StatusIndicatorTone.Idle,
            )
        }

        !uiState.desync.hasUdpFakeBurst -> {
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_saved_title),
                body = stringResource(R.string.quic_fake_profile_saved_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        uiState.isServiceRunning -> {
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_restart_title),
                body = stringResource(R.string.quic_fake_profile_restart_body),
                tone = StatusIndicatorTone.Warning,
            )
        }

        else -> {
            QuicFakeStatusContent(
                label = stringResource(R.string.quic_fake_profile_ready_title),
                body = stringResource(R.string.quic_fake_profile_ready_body),
                tone = StatusIndicatorTone.Active,
            )
        }
    }
