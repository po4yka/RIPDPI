package com.poyka.ripdpi.ui.screens.settings

import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.poyka.ripdpi.R
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.LanguagePickerSheet
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun SettingsConnectivitySection(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    SettingsSection(title = stringResource(R.string.settings_connectivity_section)) {
        SettingsRow(
            title = stringResource(R.string.title_dns_settings),
            subtitle =
                stringResource(
                    if (uiState.isVpn) {
                        R.string.settings_connectivity_dns_body
                    } else {
                        R.string.settings_connectivity_dns_body_proxy
                    },
                ),
            value = uiState.dns.dnsSummary,
            onClick = actions.onOpenDnsSettings,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsDnsSettings,
        )
        SettingsRow(
            title = stringResource(R.string.title_advanced_settings),
            subtitle = stringResource(R.string.settings_advanced_body),
            value = stringResource(R.string.settings_manage_action),
            onClick = actions.onOpenAdvancedSettings,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsAdvancedSettings,
        )
        SettingsRow(
            title = stringResource(R.string.settings_language_title),
            subtitle = stringResource(R.string.settings_language_body),
            value = stringResource(R.string.settings_manage_action),
            onClick = { showLanguagePicker = true },
        )
    }
    if (showLanguagePicker) {
        LanguagePickerSheet(onDismissRequest = { showLanguagePicker = false })
    }
}

@Composable
internal fun SettingsSecuritySection(
    uiState: SettingsUiState,
    localState: SettingsScreenLocalState,
    actions: SettingsScreenActions,
) {
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = stringResource(R.string.settings_security_section))
        SettingsVpnSecurityWarnings(visible = uiState.isVpn)
        AnimatedVisibility(
            visible = uiState.biometricEnabled && !uiState.hasBackupPin && localState.backupPinDraft.isBlank(),
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            WarningBanner(
                title = stringResource(R.string.settings_warning_backup_pin_title),
                message = stringResource(R.string.settings_warning_backup_pin_body),
                tone = WarningBannerTone.Restricted,
                testTag = RipDpiTestTags.SettingsBackupPinWarning,
            )
        }
        RipDpiCard {
            SecurityToggleRows(
                uiState = uiState,
                actions = actions,
                localState = localState,
            )
        }
    }
}

@Composable
private fun SettingsVpnSecurityWarnings(visible: Boolean) {
    if (!visible) return
    WarningBanner(
        title = stringResource(R.string.settings_vpn_flag_warning_title),
        message = stringResource(R.string.settings_vpn_flag_warning_body),
        tone = WarningBannerTone.Info,
    )
    WarningBanner(
        title = stringResource(R.string.settings_tethering_dns_warning_title),
        message = stringResource(R.string.settings_tethering_dns_warning_body),
        tone = WarningBannerTone.Warning,
    )
}

@Composable
private fun SecurityToggleRows(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    localState: SettingsScreenLocalState,
) {
    val motion = RipDpiThemeTokens.motion

    SettingsRow(
        title = stringResource(R.string.settings_webrtc_title),
        subtitle = stringResource(R.string.settings_webrtc_body),
        checked = uiState.webrtcProtectionEnabled,
        onCheckedChange = actions.onWebRtcProtectionChanged,
        showDivider = true,
        testTag = RipDpiTestTags.SettingsWebRtcProtection,
    )
    FullTunnelExclusionsRows(uiState, actions)
    SettingsRow(
        title = stringResource(R.string.settings_biometric_title),
        subtitle = stringResource(biometricSubtitleRes(uiState)),
        checked = uiState.biometricEnabled,
        enabled = uiState.isBiometricHardwareAvailable,
        onCheckedChange = { enabled ->
            handleBiometricToggle(enabled, uiState, localState, actions)
        },
        showDivider = localState.showBackupPinEditor,
        testTag = RipDpiTestTags.SettingsBiometric,
    )
    AnimatedVisibility(
        visible = localState.showBackupPinEditor,
        enter = motion.sectionEnterTransition(),
        exit = motion.sectionExitTransition(),
    ) {
        BackupPinEditor(
            value = localState.backupPinDraft,
            errorText = localState.pinErrorText,
            hasSavedPin = uiState.hasBackupPin,
            onValueChange = localState.onBackupPinDraftChanged,
            onSave = { actions.onSaveBackupPin(localState.backupPinDraft) },
            onClear = {
                localState.onBackupPinDraftCleared()
                actions.onSaveBackupPin("")
            },
            canSave = localState.canSaveBackupPin,
        )
    }
}

@Composable
private fun FullTunnelExclusionsRows(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    Column {
        SettingsRow(
            title = stringResource(R.string.settings_exclude_russian_apps_title),
            subtitle = stringResource(R.string.settings_exclude_russian_apps_body),
            checked = uiState.excludeRussianAppsEnabled,
            onCheckedChange = actions.onExcludeRussianAppsChanged,
            enabled = !uiState.fullTunnelMode,
            showDivider = !uiState.fullTunnelMode,
        )
        FullTunnelHelperFooter(
            visible = uiState.fullTunnelMode,
            textRes = R.string.settings_full_tunnel_exclusions_disabled,
        )
    }
    Column {
        SettingsRow(
            title = stringResource(R.string.settings_full_tunnel_title),
            subtitle = stringResource(R.string.settings_full_tunnel_body),
            checked = uiState.fullTunnelMode,
            onCheckedChange = actions.onFullTunnelModeChanged,
            showDivider = !uiState.fullTunnelMode,
        )
        FullTunnelHelperFooter(
            visible = uiState.fullTunnelMode,
            textRes = R.string.settings_full_tunnel_helper,
        )
    }
}

private fun handleBiometricToggle(
    enabled: Boolean,
    uiState: SettingsUiState,
    localState: SettingsScreenLocalState,
    actions: SettingsScreenActions,
) {
    if (!enabled) {
        actions.onBiometricChanged(false)
        return
    }
    if (uiState.hasBackupPin) {
        localState.onShowBiometricConfirmDialogChanged(true)
    } else {
        localState.onShowPinRequiredDialogChanged(true)
    }
}

@Composable
internal fun SettingsAppearanceSection(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    val colors = RipDpiThemeTokens.colors
    val themeLabels = stringArrayResource(R.array.themes)
    val themeEntries = stringArrayResource(R.array.themes_entries)
    val themeOptions =
        remember(themeLabels, themeEntries) {
            themeLabels
                .zip(themeEntries)
                .map { (label, value) ->
                    RipDpiDropdownOption(value = value, label = label)
                }.toImmutableList()
        }

    SettingsSection(title = stringResource(R.string.settings_appearance_section)) {
        Text(
            text = stringResource(R.string.theme_settings),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.settings_theme_body),
            style = RipDpiThemeTokens.type.body,
            color = colors.mutedForeground,
        )
        RipDpiDropdown(
            options = themeOptions,
            selectedValue = uiState.appTheme,
            onValueSelected = actions.onThemeSelected,
            helperText = stringResource(R.string.settings_theme_helper),
            testTag = RipDpiTestTags.SettingsThemeDropdown,
            optionTagForValue = { value ->
                RipDpiTestTags.dropdownOption(RipDpiTestTags.SettingsThemeDropdown, value)
            },
        )
        HorizontalDivider(color = colors.divider)
        SettingsRow(
            title = stringResource(R.string.title_app_customization),
            subtitle = stringResource(R.string.settings_customization_body),
            value = stringResource(R.string.settings_manage_action),
            onClick = actions.onOpenCustomization,
            testTag = RipDpiTestTags.SettingsCustomization,
        )
    }
}

@Composable
internal fun SettingsPermissionsSection(
    permissionSummary: PermissionSummaryUiState,
    actions: SettingsScreenActions,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion

    SettingsSection(title = stringResource(R.string.settings_permissions_section)) {
        AnimatedVisibility(
            visible = permissionSummary.backgroundGuidance != null,
            enter = motion.sectionEnterTransition(),
            exit = motion.sectionExitTransition(),
        ) {
            permissionSummary.backgroundGuidance?.let { guidance ->
                Column {
                    WarningBanner(
                        title = guidance.title,
                        message = guidance.message,
                        tone = WarningBannerTone.Info,
                        testTag = RipDpiTestTags.SettingsBackgroundGuidanceBanner,
                        onDismiss = actions.onDismissBackgroundGuidance,
                    )
                    HorizontalDivider(color = colors.divider)
                }
            }
        }
        permissionSummary.items.forEachIndexed { index, item ->
            SettingsRow(
                title = item.title,
                subtitle = item.subtitle,
                value = item.actionLabel ?: item.statusLabel,
                onClick =
                    item.actionLabel?.let {
                        if (item.kind == PermissionKind.VpnConsent) {
                            actions.onOpenVpnPermissionDialog
                        } else {
                            { actions.onRepairPermission(item.kind) }
                        }
                    },
                enabled = item.enabled,
                showDivider = index != permissionSummary.items.lastIndex,
                testTag = RipDpiTestTags.settingsPermission(item.kind),
            )
        }
    }
}

@Composable
internal fun SettingsSupportSection(
    communityApiUrlDraft: String,
    appVersionName: String,
    actions: SettingsScreenActions,
    onCommunityApiUrlDraftChanged: (String) -> Unit,
) {
    val colors = RipDpiThemeTokens.colors

    SettingsSection(title = stringResource(R.string.settings_support_section)) {
        SettingsRow(
            title = stringResource(R.string.settings_support_debug_bundle_title),
            subtitle = stringResource(R.string.settings_support_debug_bundle_body),
            value = stringResource(R.string.settings_share_debug_bundle_action),
            onClick = actions.onShareDebugBundle,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsSupportBundle,
        )
        SettingsRow(
            title = stringResource(R.string.title_detection_check),
            subtitle = stringResource(R.string.detection_check_subtitle),
            onClick = actions.onOpenDetectionCheck,
            showDivider = true,
        )
        CommunityApiUrlEditor(
            value = communityApiUrlDraft,
            onValueChange = onCommunityApiUrlDraftChanged,
            onSave = { actions.onCommunityApiUrlChanged(communityApiUrlDraft) },
            onReset = {
                onCommunityApiUrlDraftChanged("")
                actions.onCommunityApiUrlChanged("")
            },
        )
        RipDpiButton(
            text = stringResource(R.string.settings_clear_community_cache),
            onClick = actions.onClearCommunityCache,
            modifier = Modifier.fillMaxWidth(),
            variant = RipDpiButtonVariant.Outline,
        )
        HorizontalDivider(color = colors.divider)
        SettingsRow(
            title = stringResource(R.string.title_data_transparency),
            subtitle = stringResource(R.string.settings_data_transparency_body),
            value = stringResource(R.string.settings_manage_action),
            onClick = actions.onOpenDataTransparency,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsDataTransparency,
        )
        SettingsRow(
            title = stringResource(R.string.about_category),
            subtitle = stringResource(R.string.settings_about_body),
            value = appVersionName,
            onClick = actions.onOpenAbout,
            testTag = RipDpiTestTags.SettingsAbout,
        )
    }
}

@Composable
internal fun SettingsDangerSection(onResetClick: () -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_danger_section)) {
        SettingsRow(
            title = stringResource(R.string.settings_reset_title),
            subtitle = stringResource(R.string.settings_reset_body),
            value = stringResource(R.string.settings_reset_action),
            onClick = onResetClick,
        )
    }
}

private fun biometricSubtitleRes(uiState: SettingsUiState): Int =
    when {
        !uiState.isBiometricHardwareAvailable -> {
            when (uiState.biometricAvailability) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                    R.string.settings_biometric_no_enrollment
                }

                else -> {
                    R.string.settings_biometric_unavailable
                }
            }
        }

        uiState.biometricEnabled && uiState.hasBackupPin -> {
            R.string.settings_biometric_body_with_pin
        }

        uiState.biometricEnabled -> {
            R.string.settings_biometric_body_without_pin
        }

        else -> {
            R.string.settings_biometric_body_disabled
        }
    }

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        SettingsCategoryHeader(title = title)
        RipDpiCard(content = { content() })
    }
}

@Composable
private fun BackupPinEditor(
    value: String,
    errorText: String?,
    hasSavedPin: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    canSave: Boolean,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.biometric_prompt_pin_title),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Text(
            text = stringResource(R.string.settings_backup_pin_body),
            style = type.body,
            color = colors.mutedForeground,
        )
        RipDpiTextField(
            value = value,
            onValueChange = onValueChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.biometric_prompt_pin_label),
                    placeholder = stringResource(R.string.biometric_prompt_pin_placeholder),
                    helperText =
                        if (hasSavedPin && errorText == null && value.length == 4) {
                            stringResource(R.string.settings_backup_pin_helper_saved)
                        } else {
                            stringResource(R.string.biometric_prompt_pin_helper)
                        },
                    errorText = errorText,
                    testTag = RipDpiTestTags.SettingsBackupPinField,
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (canSave) {
                                    onSave()
                                }
                            },
                        ),
                    visualTransformation = PasswordVisualTransformation(),
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.settings_backup_pin_save),
                onClick = onSave,
                enabled = canSave,
                modifier =
                    Modifier
                        .weight(1f)
                        .ripDpiTestTag(RipDpiTestTags.SettingsBackupPinSave),
            )
            if (hasSavedPin) {
                RipDpiButton(
                    text = stringResource(R.string.settings_backup_pin_clear),
                    onClick = onClear,
                    modifier =
                        Modifier
                            .weight(1f)
                            .ripDpiTestTag(RipDpiTestTags.SettingsBackupPinClear),
                    variant = RipDpiButtonVariant.Outline,
                )
            }
        }
    }
}

@Composable
private fun FullTunnelHelperFooter(
    visible: Boolean,
    @StringRes textRes: Int,
) {
    if (!visible) return
    Text(
        text = stringResource(textRes),
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
        modifier =
            Modifier.padding(
                start = RipDpiThemeTokens.spacing.md,
                end = RipDpiThemeTokens.spacing.md,
                bottom = RipDpiThemeTokens.spacing.sm,
            ),
    )
    HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
}

@Composable
private fun CommunityApiUrlEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.settings_community_api_url_label),
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        RipDpiTextField(
            value = value,
            onValueChange = onValueChange,
            decoration =
                RipDpiTextFieldDecoration(
                    label = stringResource(R.string.settings_community_api_url_label),
                    placeholder = "https://",
                    helperText = stringResource(R.string.settings_community_api_url_helper),
                ),
            behavior =
                RipDpiTextFieldBehavior(
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { onSave() },
                        ),
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.settings_community_api_url_save),
                onClick = onSave,
                modifier = Modifier.weight(1f),
            )
            RipDpiButton(
                text = stringResource(R.string.settings_community_api_url_reset),
                onClick = onReset,
                enabled = value.isNotBlank(),
                modifier = Modifier.weight(1f),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}
