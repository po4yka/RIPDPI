package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.activities.hashPin
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun SettingsRoute(
    onOpenDnsSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenAbout: () -> Unit,
    onShareDebugBundle: () -> Unit,
    permissionSummary: PermissionSummaryUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        uiState = uiState,
        onOpenDnsSettings = onOpenDnsSettings,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenCustomization = onOpenCustomization,
        onOpenAbout = onOpenAbout,
        onShareDebugBundle = onShareDebugBundle,
        permissionSummary = permissionSummary,
        onRepairPermission = onRepairPermission,
        onThemeSelected = viewModel::setAppTheme,
        onWebRtcProtectionChanged = viewModel::setWebRtcProtectionEnabled,
        onBiometricChanged = viewModel::setBiometricEnabled,
        onSaveBackupPin = viewModel::setBackupPin,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onOpenDnsSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenAbout: () -> Unit,
    onShareDebugBundle: () -> Unit,
    permissionSummary: PermissionSummaryUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onThemeSelected: (String) -> Unit,
    onWebRtcProtectionChanged: (Boolean) -> Unit,
    onBiometricChanged: (Boolean) -> Unit,
    onSaveBackupPin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val spacing = RipDpiThemeTokens.spacing
    val themeOptions =
        stringArrayResource(R.array.themes)
            .zip(stringArrayResource(R.array.themes_entries))
            .map { (label, value) ->
                RipDpiDropdownOption(
                    value = value,
                    label = label,
                )
            }
    var backupPinDraft by rememberSaveable { mutableStateOf("") }
    val pinErrorText =
        when {
            backupPinDraft.isNotEmpty() && backupPinDraft.length < 4 -> {
                stringResource(R.string.settings_backup_pin_error)
            }

            else -> {
                null
            }
        }
    val canSaveBackupPin = backupPinDraft.length == 4
    val showBackupPinEditor = uiState.biometricEnabled || uiState.hasBackupPin || backupPinDraft.isNotBlank()

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.settings),
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.settings_connectivity_section),
            ) {
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
                    value = uiState.dnsSummary,
                    onClick = onOpenDnsSettings,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.title_advanced_settings),
                    subtitle = stringResource(R.string.settings_advanced_body),
                    value = stringResource(R.string.settings_manage_action),
                    onClick = onOpenAdvancedSettings,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(
                    title = stringResource(R.string.settings_security_section),
                )

                if (uiState.biometricEnabled && !uiState.hasBackupPin && backupPinDraft.isBlank()) {
                    WarningBanner(
                        title = stringResource(R.string.settings_warning_backup_pin_title),
                        message = stringResource(R.string.settings_warning_backup_pin_body),
                        tone = WarningBannerTone.Restricted,
                    )
                }

                RipDpiCard {
                    SettingsRow(
                        title = stringResource(R.string.settings_webrtc_title),
                        subtitle = stringResource(R.string.settings_webrtc_body),
                        checked = uiState.webrtcProtectionEnabled,
                        onCheckedChange = onWebRtcProtectionChanged,
                        showDivider = true,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_biometric_title),
                        subtitle =
                            stringResource(
                                when {
                                    uiState.biometricEnabled && uiState.hasBackupPin -> {
                                        R.string.settings_biometric_body_with_pin
                                    }

                                    uiState.biometricEnabled -> {
                                        R.string.settings_biometric_body_without_pin
                                    }

                                    else -> {
                                        R.string.settings_biometric_body_disabled
                                    }
                                },
                            ),
                        checked = uiState.biometricEnabled,
                        onCheckedChange = onBiometricChanged,
                        showDivider = showBackupPinEditor,
                    )

                    if (showBackupPinEditor) {
                        BackupPinEditor(
                            value = backupPinDraft,
                            errorText = pinErrorText,
                            hasSavedPin = uiState.hasBackupPin,
                            onValueChange = { next ->
                                backupPinDraft = next.filter(Char::isDigit).take(4)
                            },
                            onSave = { onSaveBackupPin(backupPinDraft) },
                            onClear = {
                                backupPinDraft = ""
                                onSaveBackupPin("")
                            },
                            canSave = canSaveBackupPin,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_appearance_section),
            ) {
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
                    onValueSelected = onThemeSelected,
                    helperText = stringResource(R.string.settings_theme_helper),
                )
                HorizontalDivider(color = colors.divider)
                SettingsRow(
                    title = stringResource(R.string.title_app_customization),
                    subtitle = stringResource(R.string.settings_customization_body),
                    value = stringResource(R.string.settings_manage_action),
                    onClick = onOpenCustomization,
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_permissions_section),
            ) {
                permissionSummary.items.forEachIndexed { index, item ->
                    SettingsRow(
                        title = item.title,
                        subtitle = item.subtitle,
                        value = item.actionLabel ?: item.statusLabel,
                        onClick = item.actionLabel?.let { { onRepairPermission(item.kind) } },
                        enabled = item.enabled,
                        showDivider = index != permissionSummary.items.lastIndex,
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_support_section),
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_support_debug_bundle_title),
                    subtitle = stringResource(R.string.settings_support_debug_bundle_body),
                    value = stringResource(R.string.settings_share_debug_bundle_action),
                    onClick = onShareDebugBundle,
                    showDivider = true,
                )
                SettingsRow(
                    title = stringResource(R.string.about_category),
                    subtitle = stringResource(R.string.settings_about_body),
                    value = BuildConfig.VERSION_NAME,
                    onClick = onOpenAbout,
                )
            }
        }
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
            label = stringResource(R.string.biometric_prompt_pin_label),
            placeholder = stringResource(R.string.biometric_prompt_pin_placeholder),
            helperText =
                if (hasSavedPin && errorText == null && value.length == 4) {
                    stringResource(R.string.settings_backup_pin_helper_saved)
                } else {
                    stringResource(R.string.biometric_prompt_pin_helper)
                },
            errorText = errorText,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        if (canSave) {
                            onSave()
                        }
                    },
                ),
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.settings_backup_pin_save),
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.weight(1f),
            )
            if (hasSavedPin) {
                RipDpiButton(
                    text = stringResource(R.string.settings_backup_pin_clear),
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    variant = RipDpiButtonVariant.Outline,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RipDpiTheme {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    dnsIp = "1.1.1.1",
                    webrtcProtectionEnabled = true,
                    biometricEnabled = true,
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onShareDebugBundle = {},
            permissionSummary = PermissionSummaryUiState(),
            onRepairPermission = {},
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    appTheme = "dark",
                    dnsIp = "9.9.9.9",
                    webrtcProtectionEnabled = true,
                    biometricEnabled = true,
                    backupPinHash = hashPin("1234"),
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onShareDebugBundle = {},
            permissionSummary = PermissionSummaryUiState(),
            onRepairPermission = {},
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
    }
}
