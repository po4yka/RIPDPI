package com.poyka.ripdpi.ui.screens.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigPreset
import com.poyka.ripdpi.activities.ConfigPresetKind
import com.poyka.ripdpi.activities.ConfigUiState
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.buildConfigPresets
import com.poyka.ripdpi.activities.toConfigDraft
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.PresetCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiContentScreenScaffold
import com.poyka.ripdpi.ui.components.scaffold.RipDpiScaffoldWidth
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun ConfigRoute(
    onOpenModeEditor: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    ConfigScreen(
        uiState = uiState,
        modifier = modifier,
        onModeSelected = viewModel::selectMode,
        onPresetSelected = { preset ->
            when (preset.kind) {
                ConfigPresetKind.Custom -> {
                    viewModel.startEditingPreset(preset.id)
                    onOpenModeEditor()
                }

                else -> {
                    viewModel.selectPreset(preset.id)
                }
            }
        },
        onEditCurrent = {
            val selectedPresetId = uiState.presets.firstOrNull { it.isSelected }?.id ?: "custom"
            viewModel.startEditingPreset(selectedPresetId)
            onOpenModeEditor()
        },
        onOpenDnsSettings = onOpenDnsSettings,
    )
}

@Composable
fun ConfigScreen(
    uiState: ConfigUiState,
    onModeSelected: (Mode) -> Unit,
    onPresetSelected: (ConfigPreset) -> Unit,
    onEditCurrent: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val selectedPreset = uiState.presets.firstOrNull { it.isSelected } ?: uiState.presets.last()
    val desyncSummary = uiState.draft.chainSummary

    RipDpiContentScreenScaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.config),
        contentWidth = RipDpiScaffoldWidth.Content,
    ) {
        if (uiState.draft.useCommandLineSettings) {
            WarningBanner(
                title = stringResource(R.string.config_cli_banner_title),
                message = stringResource(R.string.config_cli_banner_body),
                tone = WarningBannerTone.Restricted,
            )
        }

        RipDpiCard {
            Text(
                text = stringResource(R.string.config_overview_title),
                style = type.sectionTitle,
                color = colors.mutedForeground,
            )
            Text(
                text = stringResource(titleResForPreset(selectedPreset.kind)),
                style = type.screenTitle,
                color = colors.foreground,
            )
            Text(
                text = stringResource(descriptionResForPreset(selectedPreset.kind)),
                style = type.body,
                color = colors.mutedForeground,
            )

            ConfigModeChips(
                selectedMode = uiState.activeMode,
                onModeSelected = onModeSelected,
            )

            RipDpiButton(
                text = stringResource(R.string.config_edit_current),
                onClick = onEditCurrent,
                variant =
                    if (selectedPreset.kind == ConfigPresetKind.Custom) {
                        RipDpiButtonVariant.Primary
                    } else {
                        RipDpiButtonVariant.Outline
                    },
                trailingIcon = RipDpiIcons.ChevronRight,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.config_presets_section))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                uiState.presets.forEach { preset ->
                    PresetCard(
                        title = stringResource(titleResForPreset(preset.kind)),
                        description = stringResource(descriptionResForPreset(preset.kind)),
                        badgeText =
                            if (preset.isSelected) {
                                stringResource(R.string.config_badge_active)
                            } else {
                                null
                            },
                        selected = preset.isSelected,
                        onClick = { onPresetSelected(preset) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            SettingsCategoryHeader(title = stringResource(R.string.config_summary_section))
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.mode_setting),
                    value = stringResource(modeLabelRes(uiState.draft.mode)),
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.title_dns_settings),
                    subtitle =
                        stringResource(
                            if (uiState.draft.mode == Mode.VPN) {
                                R.string.config_dns_summary_enabled
                            } else {
                                R.string.config_dns_summary_disabled
                            },
                        ),
                    value = uiState.draft.dnsSummary,
                    onClick = onOpenDnsSettings,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.bye_dpi_proxy_ip_setting),
                    value =
                        stringResource(
                            R.string.proxy_address,
                            uiState.draft.proxyIp,
                            uiState.draft.proxyPort,
                        ),
                    monospaceValue = true,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.ripdpi_desync_method_setting),
                    value = desyncSummary,
                    showDivider = uiState.draft.defaultTtl.isNotBlank(),
                )
                if (uiState.draft.defaultTtl.isNotBlank()) {
                    SettingsRow(
                        title = stringResource(R.string.ripdpi_default_ttl_setting),
                        value = uiState.draft.defaultTtl,
                        monospaceValue = true,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConfigModeChips(
    selectedMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        RipDpiChip(
            text = stringResource(modeLabelRes(Mode.VPN)),
            selected = selectedMode == Mode.VPN,
            onClick = { onModeSelected(Mode.VPN) },
        )
        RipDpiChip(
            text = stringResource(modeLabelRes(Mode.Proxy)),
            selected = selectedMode == Mode.Proxy,
            onClick = { onModeSelected(Mode.Proxy) },
        )
    }
}

internal fun titleResForPreset(kind: ConfigPresetKind): Int =
    when (kind) {
        ConfigPresetKind.Recommended -> R.string.config_preset_recommended_title
        ConfigPresetKind.Proxy -> R.string.config_preset_proxy_title
        ConfigPresetKind.Custom -> R.string.config_preset_custom_title
    }

private fun descriptionResForPreset(kind: ConfigPresetKind): Int =
    when (kind) {
        ConfigPresetKind.Recommended -> R.string.config_preset_recommended_body
        ConfigPresetKind.Proxy -> R.string.config_preset_proxy_body
        ConfigPresetKind.Custom -> R.string.config_preset_custom_body
    }

internal fun modeLabelRes(mode: Mode): Int =
    when (mode) {
        Mode.VPN -> R.string.home_mode_vpn
        Mode.Proxy -> R.string.home_mode_proxy
    }

@Preview(showBackground = true)
@Composable
private fun ConfigScreenPreview() {
    RipDpiTheme {
        ConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = Mode.VPN,
                    presets = buildConfigPresets(AppSettingsSerializer.defaultValue.toConfigDraft()),
                    draft = AppSettingsSerializer.defaultValue.toConfigDraft(),
                ),
            onModeSelected = {},
            onPresetSelected = {},
            onEditCurrent = {},
            onOpenDnsSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfigScreenDarkPreview() {
    val draft =
        AppSettingsSerializer.defaultValue.toConfigDraft().copy(
            mode = Mode.Proxy,
            proxyIp = "192.168.0.4",
            proxyPort = "1086",
            useCommandLineSettings = true,
            commandLineArgs = "--fake --split 2",
            defaultTtl = "12",
        )
    RipDpiTheme(themePreference = "dark") {
        ConfigScreen(
            uiState =
                ConfigUiState(
                    activeMode = draft.mode,
                    presets = buildConfigPresets(draft),
                    draft = draft,
                ),
            onModeSelected = {},
            onPresetSelected = {},
            onEditCurrent = {},
            onOpenDnsSettings = {},
        )
    }
}
