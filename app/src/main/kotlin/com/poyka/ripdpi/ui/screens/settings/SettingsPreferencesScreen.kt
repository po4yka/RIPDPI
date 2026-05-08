package com.poyka.ripdpi.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

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
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState.value,
        actions =
            SettingsScreenActions(
                onOpenDnsSettings = onOpenDnsSettings,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onOpenCustomization = onOpenCustomization,
                onOpenAbout = onOpenAbout,
                onOpenDataTransparency = onOpenDataTransparency,
                onOpenDetectionCheck = onOpenDetectionCheck,
                onShareDebugBundle = onShareDebugBundle,
                onRepairPermission = onRepairPermission,
                onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
                onDismissBackgroundGuidance = onDismissBackgroundGuidance,
                onThemeSelected = remember(viewModel) { viewModel::setAppTheme },
                onWebRtcProtectionChanged = remember(viewModel) { viewModel::setWebRtcProtectionEnabled },
                onExcludeRussianAppsChanged = remember(viewModel) { viewModel::setExcludeRussianAppsEnabled },
                onFullTunnelModeChanged = remember(viewModel) { viewModel::setFullTunnelMode },
                onBiometricChanged = remember(viewModel) { viewModel::setBiometricEnabled },
                onSaveBackupPin = remember(viewModel) { viewModel::setBackupPin },
                onResetSettings = remember(viewModel) { viewModel::resetSettings },
                onCommunityApiUrlChanged = remember(viewModel) { viewModel::setCommunityApiUrl },
                onClearCommunityCache = remember(viewModel) { viewModel::clearCommunityCache },
            ),
        permissionSummary = permissionSummary,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    permissionSummary: PermissionSummaryUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val localState = rememberSettingsScreenLocalState(uiState)

    SettingsPreferenceDialogs(localState = localState, actions = actions)
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Settings))
                .fillMaxSize()
                .background(colors.background),
        title = stringResource(R.string.settings),
    ) {
        item { SettingsConnectivitySection(uiState = uiState, actions = actions) }
        item { SettingsSecuritySection(uiState = uiState, localState = localState, actions = actions) }
        item { SettingsAppearanceSection(uiState = uiState, actions = actions) }
        item { SettingsPermissionsSection(permissionSummary = permissionSummary, actions = actions) }
        item {
            SettingsSupportSection(
                communityApiUrlDraft = localState.communityApiUrlDraft,
                appVersionName = BuildConfig.VERSION_NAME,
                actions = actions,
                onCommunityApiUrlDraftChanged = localState.onCommunityApiUrlDraftChanged,
            )
        }
        item {
            SettingsDangerSection(
                onResetClick = { localState.onShowResetConfirmDialogChanged(true) },
            )
        }
    }
}

internal data class SettingsScreenActions(
    val onOpenDnsSettings: () -> Unit,
    val onOpenAdvancedSettings: () -> Unit,
    val onOpenCustomization: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenDataTransparency: () -> Unit,
    val onOpenDetectionCheck: () -> Unit = {},
    val onShareDebugBundle: () -> Unit,
    val onRepairPermission: (PermissionKind) -> Unit,
    val onOpenVpnPermissionDialog: () -> Unit,
    val onDismissBackgroundGuidance: () -> Unit = {},
    val onThemeSelected: (String) -> Unit,
    val onWebRtcProtectionChanged: (Boolean) -> Unit,
    val onExcludeRussianAppsChanged: (Boolean) -> Unit,
    val onFullTunnelModeChanged: (Boolean) -> Unit,
    val onBiometricChanged: (Boolean) -> Unit,
    val onSaveBackupPin: (String) -> Unit,
    val onResetSettings: () -> Unit = {},
    val onCommunityApiUrlChanged: (String) -> Unit = {},
    val onClearCommunityCache: () -> Unit = {},
)

private fun previewActions(): SettingsScreenActions =
    SettingsScreenActions(
        onOpenDnsSettings = {},
        onOpenAdvancedSettings = {},
        onOpenCustomization = {},
        onOpenAbout = {},
        onOpenDataTransparency = {},
        onOpenDetectionCheck = {},
        onShareDebugBundle = {},
        onRepairPermission = {},
        onOpenVpnPermissionDialog = {},
        onThemeSelected = {},
        onWebRtcProtectionChanged = {},
        onExcludeRussianAppsChanged = {},
        onFullTunnelModeChanged = {},
        onBiometricChanged = {},
        onSaveBackupPin = {},
    )

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
            actions = previewActions(),
            permissionSummary = PermissionSummaryUiState(),
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
            actions = previewActions(),
            permissionSummary = PermissionSummaryUiState(),
        )
    }
}
