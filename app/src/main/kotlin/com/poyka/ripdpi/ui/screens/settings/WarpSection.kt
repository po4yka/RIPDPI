package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.data.WarpAmneziaPresetCustom
import com.poyka.ripdpi.data.WarpAmneziaPresetOff
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.utility.checkIp
import com.poyka.ripdpi.utility.validateIntRange
import com.poyka.ripdpi.utility.validatePort

private const val warpScannerParallelismMax = 64
private const val warpScannerRttMinMs = 50
private const val warpScannerRttMaxMs = 10_000

@Suppress("LongMethod")
internal fun LazyListScope.warpSection(
    uiState: SettingsUiState,
    visualEditorEnabled: Boolean,
    routeModeOptions: List<RipDpiDropdownOption<String>>,
    endpointSelectionOptions: List<RipDpiDropdownOption<String>>,
    amneziaPresetOptions: List<RipDpiDropdownOption<String>>,
    onToggleChanged: (AdvancedToggleSetting, Boolean) -> Unit,
    onOptionSelected: (AdvancedOptionSetting, String) -> Unit,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
) {
    item(key = "advanced_warp") {
        val sectionEnabled = visualEditorEnabled && uiState.warpUiAvailable
        val disabledMessage =
            if (uiState.warpUiAvailable) {
                stringResource(R.string.advanced_settings_visual_controls_disabled)
            } else {
                stringResource(R.string.advanced_settings_command_line_disabled)
            }
        val numericKeyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            )
        AdvancedSettingsSection(
            title = stringResource(R.string.warp_section_title),
            testTag = RipDpiTestTags.advancedSection("warp"),
        ) {
            RipDpiCard {
                SettingsRow(
                    title = stringResource(R.string.warp_profile_id_title),
                    subtitle = stringResource(R.string.warp_profile_id_body),
                    value = uiState.warp.profileId,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.warp_account_kind_title),
                    subtitle = stringResource(R.string.warp_account_kind_body),
                    value = uiState.warp.accountKind,
                    showDivider = true,
                )
                if (uiState.warp.hasZeroTrustOrganization) {
                    SettingsRow(
                        title = stringResource(R.string.warp_zero_trust_org_title),
                        subtitle = stringResource(R.string.warp_zero_trust_org_body),
                        value = uiState.warp.zeroTrustOrg,
                        showDivider = true,
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.warp_setup_state_title),
                    subtitle = stringResource(R.string.warp_setup_state_body),
                    value = uiState.warp.setupState,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.warp_last_scanner_mode_title),
                    subtitle = stringResource(R.string.warp_last_scanner_mode_body),
                    value = uiState.warp.lastScannerMode,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.warp_enabled_title),
                    subtitle =
                        if (uiState.warpUiAvailable) {
                            stringResource(R.string.warp_enabled_body)
                        } else {
                            stringResource(R.string.advanced_settings_command_line_disabled)
                        },
                    checked = uiState.warp.enabled,
                    enabled = sectionEnabled,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.WarpEnabled, it) },
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.WarpEnabled),
                )
                AdvancedDropdownSetting(
                    title = stringResource(R.string.warp_route_mode_title),
                    description = stringResource(R.string.warp_route_mode_body),
                    value = uiState.warp.routeMode,
                    enabled = sectionEnabled,
                    options = routeModeOptions,
                    setting = AdvancedOptionSetting.WarpRouteMode,
                    onSelected = onOptionSelected,
                    showDivider = uiState.warp.routeRulesEnabled,
                )
                if (uiState.warp.routeRulesEnabled) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_route_hosts_title),
                        description = stringResource(R.string.warp_route_hosts_body),
                        value = uiState.warp.routeHosts,
                        enabled = sectionEnabled,
                        multiline = true,
                        disabledMessage = disabledMessage,
                        setting = AdvancedTextSetting.WarpRouteHosts,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.warp_builtin_rules_title),
                    subtitle = stringResource(R.string.warp_builtin_rules_body),
                    checked = uiState.warp.builtInRulesEnabled,
                    enabled = sectionEnabled && uiState.warp.enabled,
                    onCheckedChange = { onToggleChanged(AdvancedToggleSetting.WarpBuiltInRulesEnabled, it) },
                    showDivider = true,
                    testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.WarpBuiltInRulesEnabled),
                )
                AdvancedDropdownSetting(
                    title = stringResource(R.string.warp_endpoint_mode_title),
                    description = stringResource(R.string.warp_endpoint_mode_body),
                    value = uiState.warp.endpointSelectionMode,
                    enabled = sectionEnabled && uiState.warp.enabled,
                    options = endpointSelectionOptions,
                    setting = AdvancedOptionSetting.WarpEndpointSelectionMode,
                    onSelected = onOptionSelected,
                    showDivider = uiState.warp.manualEndpointEnabled,
                )
                if (uiState.warp.manualEndpointEnabled) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_manual_host_title),
                        description = stringResource(R.string.warp_manual_host_body),
                        value = uiState.warp.manualEndpointHost,
                        enabled = sectionEnabled,
                        disabledMessage = disabledMessage,
                        setting = AdvancedTextSetting.WarpManualEndpointHost,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_manual_ipv4_title),
                        description = stringResource(R.string.warp_manual_ipv4_body),
                        value = uiState.warp.manualEndpointIpv4,
                        enabled = sectionEnabled,
                        validator = { it.isBlank() || checkIp(it) },
                        invalidMessage = stringResource(R.string.config_error_invalid_proxy_ip),
                        disabledMessage = disabledMessage,
                        setting = AdvancedTextSetting.WarpManualEndpointIpv4,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_manual_ipv6_title),
                        description = stringResource(R.string.warp_manual_ipv6_body),
                        value = uiState.warp.manualEndpointIpv6,
                        enabled = sectionEnabled,
                        disabledMessage = disabledMessage,
                        setting = AdvancedTextSetting.WarpManualEndpointIpv6,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_manual_port_title),
                        description = stringResource(R.string.warp_manual_port_body),
                        value = uiState.warp.manualEndpointPort.toString(),
                        enabled = sectionEnabled,
                        validator = ::validatePort,
                        invalidMessage = stringResource(R.string.config_error_invalid_port),
                        disabledMessage = disabledMessage,
                        keyboardOptions = numericKeyboardOptions,
                        setting = AdvancedTextSetting.WarpManualEndpointPort,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                if (uiState.warp.scannerAvailable) {
                    SettingsRow(
                        title = stringResource(R.string.warp_scanner_enabled_title),
                        subtitle = stringResource(R.string.warp_scanner_enabled_body),
                        checked = uiState.warp.scannerEnabled,
                        enabled = sectionEnabled && uiState.warp.enabled,
                        onCheckedChange = { onToggleChanged(AdvancedToggleSetting.WarpScannerEnabled, it) },
                        showDivider = uiState.warp.scannerControlsEnabled,
                        testTag = RipDpiTestTags.advancedToggle(AdvancedToggleSetting.WarpScannerEnabled),
                    )
                }
                if (uiState.warp.scannerControlsEnabled) {
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_scanner_parallelism_title),
                        description = stringResource(R.string.warp_scanner_parallelism_body),
                        value = uiState.warp.scannerParallelism.toString(),
                        enabled = sectionEnabled,
                        validator = { validateIntRange(it, 1, warpScannerParallelismMax) },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = disabledMessage,
                        keyboardOptions = numericKeyboardOptions,
                        setting = AdvancedTextSetting.WarpScannerParallelism,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                    AdvancedTextSetting(
                        title = stringResource(R.string.warp_scanner_rtt_title),
                        description = stringResource(R.string.warp_scanner_rtt_body),
                        value = uiState.warp.scannerMaxRttMs.toString(),
                        enabled = sectionEnabled,
                        validator = { validateIntRange(it, warpScannerRttMinMs, warpScannerRttMaxMs) },
                        invalidMessage = stringResource(R.string.config_error_out_of_range),
                        disabledMessage = disabledMessage,
                        keyboardOptions = numericKeyboardOptions,
                        setting = AdvancedTextSetting.WarpScannerMaxRttMs,
                        onConfirm = onTextConfirmed,
                        showDivider = true,
                    )
                }
                AdvancedDropdownSetting(
                    title = stringResource(R.string.warp_amnezia_preset_title),
                    description = stringResource(R.string.warp_amnezia_preset_body),
                    value = uiState.warp.amneziaPreset,
                    enabled = sectionEnabled && uiState.warp.enabled,
                    options = amneziaPresetOptions,
                    setting = AdvancedOptionSetting.WarpAmneziaPreset,
                    onSelected = onOptionSelected,
                    showDivider = uiState.warp.amneziaEnabled,
                )
                if (uiState.warp.amneziaEnabled) {
                    SettingsRow(
                        title = stringResource(R.string.warp_amnezia_profile_title),
                        subtitle = warpAmneziaSummary(uiState),
                        value =
                            amneziaPresetOptions
                                .firstOrNull { it.value == uiState.warp.amneziaPreset }
                                ?.label ?: uiState.warp.amneziaPreset,
                        showDivider = uiState.warp.amneziaControlsEnabled,
                    )
                }
                if (uiState.warp.amneziaControlsEnabled) {
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_jc_title),
                        value = uiState.warp.amneziaJc.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaJc,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_jmin_title),
                        value = uiState.warp.amneziaJmin.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaJmin,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_jmax_title),
                        value = uiState.warp.amneziaJmax.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaJmax,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_h1_title),
                        value = uiState.warp.amneziaH1.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaH1,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_h2_title),
                        value = uiState.warp.amneziaH2.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaH2,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_h3_title),
                        value = uiState.warp.amneziaH3.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaH3,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_h4_title),
                        value = uiState.warp.amneziaH4.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaH4,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_s1_title),
                        value = uiState.warp.amneziaS1.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaS1,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_s2_title),
                        value = uiState.warp.amneziaS2.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaS2,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_s3_title),
                        value = uiState.warp.amneziaS3.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaS3,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                    warpAmneziaTextSetting(
                        title = stringResource(R.string.warp_amnezia_s4_title),
                        value = uiState.warp.amneziaS4.toString(),
                        setting = AdvancedTextSetting.WarpAmneziaS4,
                        onTextConfirmed = onTextConfirmed,
                        keyboardOptions = numericKeyboardOptions,
                        disabledMessage = disabledMessage,
                    )
                }
            }
        }
    }
}

@Composable
private fun warpAmneziaSummary(uiState: SettingsUiState): String =
    when (uiState.warp.amneziaPreset) {
        WarpAmneziaPresetOff -> {
            stringResource(R.string.warp_amnezia_preset_off_summary)
        }

        WarpAmneziaPresetCustom -> {
            stringResource(
                R.string.warp_amnezia_preset_custom_summary,
                uiState.warp.amneziaJc,
                uiState.warp.amneziaJmin,
                uiState.warp.amneziaJmax,
            )
        }

        else -> {
            stringResource(
                R.string.warp_amnezia_preset_profile_summary,
                uiState.warp.amneziaJc,
                uiState.warp.amneziaJmin,
                uiState.warp.amneziaJmax,
            )
        }
    }

@Composable
private fun warpAmneziaTextSetting(
    title: String,
    value: String,
    setting: AdvancedTextSetting,
    onTextConfirmed: (AdvancedTextSetting, String) -> Unit,
    keyboardOptions: KeyboardOptions,
    disabledMessage: String,
) {
    AdvancedTextSetting(
        title = title,
        value = value,
        validator = { it.toLongOrNull() != null },
        invalidMessage = stringResource(R.string.config_error_out_of_range),
        disabledMessage = disabledMessage,
        keyboardOptions = keyboardOptions,
        setting = setting,
        onConfirm = onTextConfirmed,
        showDivider = true,
    )
}
