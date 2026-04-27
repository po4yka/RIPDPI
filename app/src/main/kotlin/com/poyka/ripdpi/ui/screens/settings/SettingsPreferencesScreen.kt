package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.SettingsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialog
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogTone
import com.poyka.ripdpi.ui.components.feedback.RipDpiDialogVisuals
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

private const val backupPinLength = 4

@Composable
fun SettingsRoute(
    onOpenDnsSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDataTransparency: () -> Unit,
    onOpenDetectionCheck: () -> Unit,
    onShareDebugBundle: () -> Unit,
    permissionSummary: PermissionSummaryUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBackgroundGuidance: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        uiState = uiState,
        onOpenDnsSettings = onOpenDnsSettings,
        onOpenAdvancedSettings = onOpenAdvancedSettings,
        onOpenCustomization = onOpenCustomization,
        onOpenAbout = onOpenAbout,
        onOpenDataTransparency = onOpenDataTransparency,
        onOpenDetectionCheck = onOpenDetectionCheck,
        onShareDebugBundle = onShareDebugBundle,
        permissionSummary = permissionSummary,
        onRepairPermission = onRepairPermission,
        onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
        onDismissBackgroundGuidance = onDismissBackgroundGuidance,
        onThemeSelected = viewModel::setAppTheme,
        onWebRtcProtectionChanged = viewModel::setWebRtcProtectionEnabled,
        onExcludeRussianAppsChanged = viewModel::setExcludeRussianAppsEnabled,
        onFullTunnelModeChanged = viewModel::setFullTunnelMode,
        onBiometricChanged = viewModel::setBiometricEnabled,
        onSaveBackupPin = viewModel::setBackupPin,
        onResetSettings = viewModel::resetSettings,
        onCommunityApiUrlChanged = viewModel::setCommunityApiUrl,
        modifier = modifier,
    )
}

@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    onOpenDnsSettings: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenCustomization: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDataTransparency: () -> Unit,
    onOpenDetectionCheck: () -> Unit = {},
    onShareDebugBundle: () -> Unit,
    permissionSummary: PermissionSummaryUiState,
    onRepairPermission: (PermissionKind) -> Unit,
    onOpenVpnPermissionDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissBackgroundGuidance: () -> Unit = {},
    onThemeSelected: (String) -> Unit,
    onWebRtcProtectionChanged: (Boolean) -> Unit,
    onExcludeRussianAppsChanged: (Boolean) -> Unit,
    onFullTunnelModeChanged: (Boolean) -> Unit,
    onBiometricChanged: (Boolean) -> Unit,
    onSaveBackupPin: (String) -> Unit,
    onResetSettings: () -> Unit = {},
    onCommunityApiUrlChanged: (String) -> Unit = {},
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val spacing = RipDpiThemeTokens.spacing
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
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showBiometricConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showPinRequiredDialog by rememberSaveable { mutableStateOf(false) }
    var backupPinDraft by rememberSaveable { mutableStateOf("") }
    var communityApiUrlDraft by rememberSaveable(uiState.communityApiUrl) { mutableStateOf(uiState.communityApiUrl) }
    val pinErrorText =
        when {
            backupPinDraft.isNotEmpty() && backupPinDraft.length < backupPinLength -> {
                stringResource(R.string.settings_backup_pin_error)
            }

            else -> {
                null
            }
        }
    val canSaveBackupPin = backupPinDraft.length == backupPinLength
    val showBackupPinEditor = uiState.biometricEnabled || uiState.hasBackupPin || backupPinDraft.isNotBlank()

    if (showPinRequiredDialog) {
        RipDpiDialog(
            onDismissRequest = { showPinRequiredDialog = false },
            title = stringResource(R.string.settings_biometric_pin_required_title),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.settings_biometric_pin_required_ok),
                    onClick = { showPinRequiredDialog = false },
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.settings_biometric_pin_required_message),
                ),
        )
    }

    BiometricConfirmDialog(
        visible = showBiometricConfirmDialog,
        onDismiss = { showBiometricConfirmDialog = false },
        onConfirm = {
            showBiometricConfirmDialog = false
            onBiometricChanged(true)
        },
    )

    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Settings))
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
                    value = uiState.dns.dnsSummary,
                    onClick = onOpenDnsSettings,
                    showDivider = true,
                    testTag = RipDpiTestTags.SettingsDnsSettings,
                )
                SettingsRow(
                    title = stringResource(R.string.title_advanced_settings),
                    subtitle = stringResource(R.string.settings_advanced_body),
                    value = stringResource(R.string.settings_manage_action),
                    onClick = onOpenAdvancedSettings,
                    testTag = RipDpiTestTags.SettingsAdvancedSettings,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                SettingsCategoryHeader(
                    title = stringResource(R.string.settings_security_section),
                )

                if (uiState.isVpn) {
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

                AnimatedVisibility(
                    visible = uiState.biometricEnabled && !uiState.hasBackupPin && backupPinDraft.isBlank(),
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
                    SettingsRow(
                        title = stringResource(R.string.settings_webrtc_title),
                        subtitle = stringResource(R.string.settings_webrtc_body),
                        checked = uiState.webrtcProtectionEnabled,
                        onCheckedChange = onWebRtcProtectionChanged,
                        showDivider = true,
                        testTag = RipDpiTestTags.SettingsWebRtcProtection,
                    )
                    Column {
                        SettingsRow(
                            title = stringResource(R.string.settings_exclude_russian_apps_title),
                            subtitle = stringResource(R.string.settings_exclude_russian_apps_body),
                            checked = uiState.excludeRussianAppsEnabled,
                            onCheckedChange = onExcludeRussianAppsChanged,
                            enabled = !uiState.fullTunnelMode,
                            showDivider = !uiState.fullTunnelMode,
                        )
                        if (uiState.fullTunnelMode) {
                            Text(
                                text = stringResource(R.string.settings_full_tunnel_exclusions_disabled),
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
                    }
                    Column {
                        SettingsRow(
                            title = stringResource(R.string.settings_full_tunnel_title),
                            subtitle = stringResource(R.string.settings_full_tunnel_body),
                            checked = uiState.fullTunnelMode,
                            onCheckedChange = onFullTunnelModeChanged,
                            showDivider = !uiState.fullTunnelMode,
                        )
                        if (uiState.fullTunnelMode) {
                            Text(
                                text = stringResource(R.string.settings_full_tunnel_helper),
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
                    }
                    SettingsRow(
                        title = stringResource(R.string.settings_biometric_title),
                        subtitle = stringResource(biometricSubtitleRes(uiState)),
                        checked = uiState.biometricEnabled,
                        enabled = uiState.isBiometricHardwareAvailable,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (uiState.hasBackupPin) {
                                    showBiometricConfirmDialog = true
                                } else {
                                    showPinRequiredDialog = true
                                }
                            } else {
                                onBiometricChanged(false)
                            }
                        },
                        showDivider = showBackupPinEditor,
                        testTag = RipDpiTestTags.SettingsBiometric,
                    )

                    AnimatedVisibility(
                        visible = showBackupPinEditor,
                        enter = motion.sectionEnterTransition(),
                        exit = motion.sectionExitTransition(),
                    ) {
                        BackupPinEditor(
                            value = backupPinDraft,
                            errorText = pinErrorText,
                            hasSavedPin = uiState.hasBackupPin,
                            onValueChange = { next ->
                                backupPinDraft = next.filter(Char::isDigit).take(backupPinLength)
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
                    onClick = onOpenCustomization,
                    testTag = RipDpiTestTags.SettingsCustomization,
                )
            }
        }

        item {
            SettingsSection(
                title = stringResource(R.string.settings_permissions_section),
            ) {
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
                                onDismiss = onDismissBackgroundGuidance,
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
                                    { onOpenVpnPermissionDialog() }
                                } else {
                                    { onRepairPermission(item.kind) }
                                }
                            },
                        enabled = item.enabled,
                        showDivider = index != permissionSummary.items.lastIndex,
                        testTag = RipDpiTestTags.settingsPermission(item.kind),
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
                    testTag = RipDpiTestTags.SettingsSupportBundle,
                )
                SettingsRow(
                    title = stringResource(R.string.title_detection_check),
                    subtitle = stringResource(R.string.detection_check_subtitle),
                    onClick = onOpenDetectionCheck,
                    showDivider = true,
                )
                CommunityApiUrlEditor(
                    value = communityApiUrlDraft,
                    onValueChange = { communityApiUrlDraft = it },
                    onSave = { onCommunityApiUrlChanged(communityApiUrlDraft) },
                    onReset = {
                        communityApiUrlDraft = ""
                        onCommunityApiUrlChanged("")
                    },
                )
                HorizontalDivider(color = colors.divider)
                SettingsRow(
                    title = stringResource(R.string.title_data_transparency),
                    subtitle = stringResource(R.string.settings_data_transparency_body),
                    value = stringResource(R.string.settings_manage_action),
                    onClick = onOpenDataTransparency,
                    showDivider = true,
                    testTag = RipDpiTestTags.SettingsDataTransparency,
                )
                SettingsRow(
                    title = stringResource(R.string.about_category),
                    subtitle = stringResource(R.string.settings_about_body),
                    value = BuildConfig.VERSION_NAME,
                    onClick = onOpenAbout,
                    testTag = RipDpiTestTags.SettingsAbout,
                )
            }
        }
        item {
            SettingsSection(
                title = stringResource(R.string.settings_danger_section),
            ) {
                SettingsRow(
                    title = stringResource(R.string.settings_reset_title),
                    subtitle = stringResource(R.string.settings_reset_body),
                    value = stringResource(R.string.settings_reset_action),
                    onClick = { showResetConfirmDialog = true },
                )
            }
        }
    }

    if (showResetConfirmDialog) {
        RipDpiDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = stringResource(R.string.settings_reset_dialog_title),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.settings_reset_dialog_body),
                    tone = RipDpiDialogTone.Destructive,
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.settings_reset_confirm),
                    onClick = {
                        onResetSettings()
                        showResetConfirmDialog = false
                    },
                ),
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.config_cancel),
                    onClick = { showResetConfirmDialog = false },
                ),
        )
    }
}

private fun biometricSubtitleRes(uiState: SettingsUiState): Int =
    when {
        !uiState.isBiometricHardwareAvailable -> {
            when (uiState.biometricAvailability) {
                androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
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
private fun BiometricConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (visible) {
        RipDpiDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.settings_biometric_confirm_title),
            dialogTestTag = RipDpiTestTags.SettingsBiometricConfirmDialog,
            dismissAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.settings_biometric_confirm_cancel),
                    onClick = onDismiss,
                    testTag = RipDpiTestTags.SettingsBiometricConfirmCancel,
                ),
            confirmAction =
                RipDpiDialogAction(
                    label = stringResource(R.string.settings_biometric_confirm_enable),
                    onClick = onConfirm,
                    testTag = RipDpiTestTags.SettingsBiometricConfirmEnable,
                ),
            visuals =
                RipDpiDialogVisuals(
                    message = stringResource(R.string.settings_biometric_confirm_message),
                ),
        )
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
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
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

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RipDpiTheme {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    dns = DnsUiState(dnsIp = "1.1.1.1"),
                    webrtcProtectionEnabled = true,
                    biometricEnabled = true,
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onOpenDetectionCheck = {},
            onShareDebugBundle = {},
            permissionSummary = PermissionSummaryUiState(),
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    RipDpiTheme(themePreference = "dark") {
        SettingsScreen(
            uiState =
                SettingsUiState(
                    appTheme = "dark",
                    dns = DnsUiState(dnsIp = "9.9.9.9"),
                    webrtcProtectionEnabled = true,
                    biometricEnabled = true,
                    backupPinHash = "preview_pin_set",
                ),
            onOpenDnsSettings = {},
            onOpenAdvancedSettings = {},
            onOpenCustomization = {},
            onOpenAbout = {},
            onOpenDataTransparency = {},
            onOpenDetectionCheck = {},
            onShareDebugBundle = {},
            permissionSummary = PermissionSummaryUiState(),
            onRepairPermission = {},
            onOpenVpnPermissionDialog = {},
            onThemeSelected = {},
            onWebRtcProtectionChanged = {},
            onExcludeRussianAppsChanged = {},
            onFullTunnelModeChanged = {},
            onBiometricChanged = {},
            onSaveBackupPin = {},
        )
    }
}
