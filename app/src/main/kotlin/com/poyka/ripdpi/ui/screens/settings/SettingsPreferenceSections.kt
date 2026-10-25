package com.poyka.ripdpi.ui.screens.settings

import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.LauncherIconManager
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.SettingsRow
import com.poyka.ripdpi.ui.components.feedback.AdvancedSection
import com.poyka.ripdpi.ui.components.feedback.WarningBanner
import com.poyka.ripdpi.ui.components.feedback.WarningBannerTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdown
import com.poyka.ripdpi.ui.components.inputs.RipDpiDropdownOption
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextField
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldBehavior
import com.poyka.ripdpi.ui.components.inputs.RipDpiTextFieldDecoration
import com.poyka.ripdpi.ui.components.navigation.SettingsCategoryHeader
import com.poyka.ripdpi.ui.screens.dns.localizedSummary
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun SettingsConnectivitySection(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    permissionSummary: PermissionSummaryUiState = PermissionSummaryUiState(),
    batteryOptimizationIgnored: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.md)) {
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
                value = uiState.dns.localizedSummary(),
                onClick = actions.onOpenDnsSettings,
                showDivider = true,
                testTag = RipDpiTestTags.SettingsDnsSettings,
            )
            SettingsRow(
                title = stringResource(R.string.subscription_status_title),
                subtitle = stringResource(R.string.settings_subscription_status_body),
                showChevron = true,
                onClick = actions.onOpenSubscriptionStatus,
                showDivider = true,
                testTag = RipDpiTestTags.SettingsSubscriptionStatus,
            )
            SettingsRow(
                title = stringResource(R.string.profile_utility_title),
                subtitle = stringResource(R.string.profile_utility_disclosure),
                showChevron = true,
                onClick = actions.onOpenProfileUtility,
                showDivider = true,
                testTag = RipDpiTestTags.SettingsProfileUtility,
            )
            SettingsRow(
                title = stringResource(R.string.title_subscription_failover),
                subtitle = stringResource(R.string.settings_subscription_failover_body),
                showChevron = true,
                onClick = actions.onOpenSubscriptionFailover,
                showDivider = true,
                testTag = RipDpiTestTags.SettingsSubscriptionFailover,
            )
            SettingsRow(
                title = stringResource(R.string.settings_start_on_boot_title),
                subtitle = stringResource(R.string.settings_start_on_boot_body),
                checked = uiState.startOnBootEnabled,
                onCheckedChange = actions.onStartOnBootChanged,
                testTag = RipDpiTestTags.SettingsStartOnBoot,
            )
        }
        if (uiState.startOnBootEnabled && !batteryOptimizationIgnored) {
            WarningBanner(
                title = stringResource(R.string.settings_start_on_boot_title),
                message = stringResource(R.string.settings_start_on_boot_battery_warning),
                tone = WarningBannerTone.Warning,
            )
        }
        SettingsPermissionsSection(permissionSummary = permissionSummary, actions = actions)
        SettingsAdvancedConnectivitySection(uiState = uiState, actions = actions)
    }
}

@Composable
private fun SettingsAdvancedConnectivitySection(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
) {
    AdvancedSection(
        initiallyExpanded = uiState.uiPersona == "advanced",
        testTag = RipDpiTestTags.SettingsAdvancedConnectivity,
    ) {
        SettingsRow(
            title = stringResource(R.string.title_advanced_settings),
            subtitle = stringResource(R.string.settings_advanced_body),
            showChevron = true,
            onClick = actions.onOpenAdvancedSettings,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsAdvancedSettings,
        )
        SettingsRow(
            title = stringResource(R.string.title_domain_bypass_list),
            subtitle = stringResource(R.string.settings_domain_bypass_body),
            showChevron = true,
            onClick = actions.onOpenDomainBypass,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsDomainBypass,
        )
        SettingsRow(
            title = stringResource(R.string.title_routes),
            subtitle = stringResource(R.string.settings_routing_rules_body),
            showChevron = true,
            onClick = actions.onOpenRoutingRules,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsRoutingRules,
        )
        SettingsRow(
            title = stringResource(R.string.title_split_tunnel),
            subtitle = stringResource(R.string.settings_split_tunnel_body),
            showChevron = true,
            onClick = actions.onOpenSplitTunnel,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsSplitTunnel,
        )
        SettingsRow(
            title = stringResource(R.string.title_root_mode_strategies),
            subtitle = stringResource(R.string.settings_root_mode_strategies_body),
            showChevron = true,
            onClick = actions.onOpenRootModeStrategies,
            testTag = RipDpiTestTags.SettingsRootModeStrategies,
        )
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
            HorizontalDivider(color = RipDpiThemeTokens.colors.divider)
            SettingsRow(
                title = stringResource(R.string.title_data_transparency),
                subtitle = stringResource(R.string.settings_data_transparency_body),
                onClick = actions.onOpenDataTransparency,
                showChevron = true,
                testTag = RipDpiTestTags.SettingsDataTransparency,
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
    // Full tunnel wins at runtime (computeAppRoutingPlan), so the stored choice is shown but inert.
    SettingsRow(
        title = stringResource(R.string.settings_exclude_russian_apps_title),
        subtitle =
            stringResource(
                if (uiState.fullTunnelMode) {
                    R.string.settings_exclude_russian_apps_full_tunnel_body
                } else {
                    R.string.settings_exclude_russian_apps_body
                },
            ),
        checked = uiState.excludeRussianAppsEnabled,
        onCheckedChange = actions.onExcludeRussianAppsChanged,
        enabled = !uiState.fullTunnelMode,
        showDivider = true,
        testTag = RipDpiTestTags.SettingsExcludeRussianApps,
    )
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
        SettingsLanguagePreference()
        HorizontalDivider(color = colors.divider)
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
        HapticFeedbackPreference(
            disabled = uiState.hapticFeedbackDisabled,
            onDisabledChange = actions.onHapticFeedbackDisabledChanged,
        )
        HorizontalDivider(color = colors.divider)
        SettingsPersonaPreference(persona = uiState.uiPersona, onPersonaSelected = actions.onPersonaSelected)
        SettingsRow(
            title = stringResource(R.string.title_app_customization),
            subtitle = stringResource(R.string.settings_customization_body),
            value = stringResource(LauncherIconManager.resolveOption(uiState.appIconVariant).labelRes),
            showChevron = true,
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

    if (permissionSummary.items.isEmpty() && permissionSummary.backgroundGuidance == null) return
    RipDpiCard {
        Text(
            text = stringResource(R.string.settings_permissions_section),
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = colors.foreground,
        )
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
                        { actions.onRepairPermission(item.kind) }
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
    onResetClick: () -> Unit,
) {
    val colors = RipDpiThemeTokens.colors

    SettingsSection(title = stringResource(R.string.settings_support_section)) {
        SettingsRow(
            title = stringResource(R.string.settings_support_debug_bundle_title),
            subtitle = stringResource(R.string.settings_support_debug_bundle_body),
            onClick = actions.onShareDebugBundle,
            showChevron = true,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsSupportBundle,
        )
        SettingsRow(
            title = stringResource(R.string.logs),
            subtitle = stringResource(R.string.settings_logs_body),
            showChevron = true,
            onClick = actions.onOpenLogs,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsLogs,
        )
        SettingsRow(
            title = stringResource(R.string.title_detection_check),
            subtitle = stringResource(R.string.detection_check_subtitle),
            onClick = actions.onOpenDetectionCheck,
            showChevron = true,
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
            title = stringResource(R.string.about_category),
            subtitle = stringResource(R.string.settings_about_body),
            value = appVersionName,
            onClick = actions.onOpenAbout,
            showDivider = true,
            testTag = RipDpiTestTags.SettingsAbout,
        )
        SettingsMaintenanceRows(actions = actions, onResetClick = onResetClick)
    }
}

@Composable
private fun SettingsMaintenanceRows(
    actions: SettingsScreenActions,
    onResetClick: () -> Unit,
) {
    SettingsRow(
        title = stringResource(R.string.title_backup_restore),
        subtitle = stringResource(R.string.settings_backup_body),
        onClick = actions.onOpenBackupRestore,
        showChevron = true,
        showDivider = true,
        testTag = RipDpiTestTags.SettingsBackupRestore,
    )
    SettingsRow(
        title = stringResource(R.string.settings_reset_title),
        subtitle = stringResource(R.string.settings_reset_body),
    )
    RipDpiButton(
        text = stringResource(R.string.settings_reset_action),
        onClick = onResetClick,
        modifier = Modifier.fillMaxWidth(),
        variant = RipDpiButtonVariant.Destructive,
        leadingIcon = RipDpiIcons.Warning,
    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupPinEditor(
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
                    onKeyboardAction =
                        KeyboardActionHandler {
                            if (canSave) {
                                onSave()
                            }
                        },
                    textObfuscationMode = TextObfuscationMode.Hidden,
                ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.settings_backup_pin_save),
                onClick = onSave,
                enabled = canSave,
                modifier =
                    Modifier
                        .ripDpiTestTag(RipDpiTestTags.SettingsBackupPinSave),
            )
            if (hasSavedPin) {
                RipDpiButton(
                    text = stringResource(R.string.settings_backup_pin_clear),
                    onClick = onClear,
                    modifier =
                        Modifier
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunityApiUrlEditor(
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
                    onKeyboardAction = KeyboardActionHandler { onSave() },
                ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RipDpiButton(
                text = stringResource(R.string.settings_community_api_url_save),
                onClick = onSave,
            )
            RipDpiButton(
                text = stringResource(R.string.settings_community_api_url_reset),
                onClick = onReset,
                enabled = value.isNotBlank(),
                variant = RipDpiButtonVariant.Outline,
            )
        }
    }
}
