package com.poyka.ripdpi.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.StartOnBootController
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.dns.DnsUiState
import com.poyka.ripdpi.ui.screens.xray.XrayProviderSettingsStatusRow
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.ripDpiScreenBackground

@Suppress("LongParameterList")
@Composable
fun SettingsRoute(
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
    onOpenDomainBypass: () -> Unit = {},
    onOpenRoutingRules: () -> Unit = {},
    onOpenSplitTunnel: () -> Unit = {},
    onOpenDistributionSettingsDetail: (Route) -> Unit = {},
    onOpenBackupRestore: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onDismissBackgroundGuidance: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val xrayProviderSnapshot = viewModel.xrayProviderSnapshot.collectAsStateWithLifecycle()
    val notice = viewModel.settingsNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startOnBootController =
        remember(context) { StartOnBootController(context.applicationContext) }
    val vendorAutostartIntents = remember(startOnBootController) { startOnBootController.vendorAutostartIntents() }
    var batteryOptimizationIgnored by remember(startOnBootController) {
        mutableStateOf(startOnBootController.isBatteryOptimizationIgnored())
    }
    LifecycleResumeEffect(startOnBootController) {
        batteryOptimizationIgnored = startOnBootController.isBatteryOptimizationIgnored()
        onPauseOrDispose {}
    }
    val customization = viewModel.customizationActions
    SettingsScreen(
        uiState = uiState.value,
        xrayProviderSnapshot = xrayProviderSnapshot.value,
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        hasVendorAutostart = vendorAutostartIntents.isNotEmpty(),
        actions =
            SettingsScreenActions(
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onOpenCustomization = onOpenCustomization,
                onOpenAbout = onOpenAbout,
                onOpenDataTransparency = onOpenDataTransparency,
                onOpenDetectionCheck = onOpenDetectionCheck,
                onOpenDomainBypass = onOpenDomainBypass,
                onOpenRoutingRules = onOpenRoutingRules,
                onOpenSplitTunnel = onOpenSplitTunnel,
                onOpenDistributionSettingsDetail = onOpenDistributionSettingsDetail,
                onOpenBackupRestore = onOpenBackupRestore,
                onOpenLogs = onOpenLogs,
                onShareDebugBundle = onShareDebugBundle,
                onRepairPermission = onRepairPermission,
                onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
                onDismissBackgroundGuidance = onDismissBackgroundGuidance,
                onThemeSelected = remember(customization) { customization::setAppTheme },
                onHapticFeedbackDisabledChanged = remember(customization) { customization::setHapticFeedbackDisabled },
                onDynamicColorChanged = remember(customization) { customization::setDynamicColorEnabled },
                onPersonaSelected = remember(customization) { customization::setUiPersona },
                onWebRtcProtectionChanged = remember(customization) { customization::setWebRtcProtectionEnabled },
                onExcludeRussianAppsChanged = remember(customization) { customization::setExcludeRussianAppsEnabled },
                onFullTunnelModeChanged = remember(customization) { customization::setFullTunnelMode },
                onBlockTrafficOnEngineFailureChanged =
                    remember(customization) { customization::setBlockTrafficOnEngineFailure },
                onFieldReportChanged = remember(viewModel) { viewModel.maintenanceActions::setFieldReportEnabled },
                onStartOnBootChanged = remember(customization) { customization::setStartOnBootEnabled },
                onOpenBootSettings = {
                    launchSettingsIntents(
                        context,
                        startOnBootController.batteryOptimizationIntents(),
                    )
                },
                onOpenVendorAutostart = { launchSettingsIntents(context, vendorAutostartIntents) },
                onBiometricChanged = remember(customization) { customization::setBiometricEnabled },
                onSaveBackupPin = remember(customization) { customization::setBackupPin },
                onResetSettings = remember(viewModel) { viewModel.maintenanceActions::resetSettings },
                onCommunityApiUrlChanged = remember(customization) { customization::setCommunityApiUrl },
                onClearCommunityCache = remember(viewModel) { viewModel::clearCommunityCache },
            ),
        permissionSummary = permissionSummary,
        notice = notice.value,
        onDismissNotice = customization::acknowledgeAppLockResetNotice,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    permissionSummary: PermissionSummaryUiState,
    modifier: Modifier = Modifier,
    xrayProviderSnapshot: XrayProviderSnapshot? = null,
    batteryOptimizationIgnored: Boolean = true,
    hasVendorAutostart: Boolean = false,
    notice: SettingsEffect.Notice? = null,
    onDismissNotice: () -> Unit = {},
) {
    val localState = rememberSettingsScreenLocalState(uiState)
    val listState = LocalSettingsListState.current ?: rememberLazyListState()
    ScrollToNewNotice(notice) { listState.animateScrollToItem(0) }

    SettingsPreferenceDialogs(localState = localState, actions = actions)
    RipDpiSettingsScaffold(
        modifier =
            modifier
                .ripDpiTestTag(RipDpiTestTags.screen(Route.Settings))
                .fillMaxSize()
                .ripDpiScreenBackground(),
        title = stringResource(R.string.settings),
        listState = listState,
    ) {
        notice?.let { item(key = "settings_notice") { SettingsNoticeBanner(it, onDismissNotice) } }
        // Embedded-Xray provider status — provider-distinct, rendered only while
        // an Xray session is active (snapshot non-null).
        if (xrayProviderSnapshot != null) {
            item { XrayProviderSettingsStatusRow(snapshot = xrayProviderSnapshot) }
        }
        item {
            SettingsConnectivitySection(
                uiState = uiState,
                actions = actions,
                permissionSummary = permissionSummary,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
                hasVendorAutostart = hasVendorAutostart,
            )
        }
        item { SettingsAppearanceSection(uiState = uiState, actions = actions) }
        item {
            SettingsSecuritySection(
                uiState = uiState,
                localState = localState,
                actions = actions,
            )
        }
        item {
            SettingsSupportSection(
                communityApiUrl = uiState.communityApiUrl,
                communityApiUrlDraft = localState.communityApiUrlDraft,
                appVersionName = BuildConfig.VERSION_NAME,
                actions = actions,
                onCommunityApiUrlDraftChanged = localState.onCommunityApiUrlDraftChanged,
                onResetClick = { localState.onShowResetConfirmDialogChanged(true) },
            )
        }
    }
}

internal data class SettingsScreenActions(
    val onOpenAdvancedSettings: () -> Unit,
    val onOpenCustomization: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenDataTransparency: () -> Unit,
    val onOpenDetectionCheck: () -> Unit = {},
    val onOpenDomainBypass: () -> Unit = {},
    val onOpenRoutingRules: () -> Unit = {},
    val onOpenSplitTunnel: () -> Unit = {},
    val onOpenDistributionSettingsDetail: (Route) -> Unit = {},
    val onOpenBackupRestore: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onShareDebugBundle: () -> Unit,
    val onRepairPermission: (PermissionKind) -> Unit,
    val onOpenVpnPermissionDialog: () -> Unit,
    val onDismissBackgroundGuidance: () -> Unit = {},
    val onThemeSelected: (String) -> Unit,
    val onHapticFeedbackDisabledChanged: (Boolean) -> Unit = {},
    val onDynamicColorChanged: (Boolean) -> Unit = {},
    val onPersonaSelected: (String) -> Unit,
    val onWebRtcProtectionChanged: (Boolean) -> Unit,
    val onExcludeRussianAppsChanged: (Boolean) -> Unit,
    val onFullTunnelModeChanged: (Boolean) -> Unit,
    val onBlockTrafficOnEngineFailureChanged: (Boolean) -> Unit = {},
    val onFieldReportChanged: (Boolean) -> Unit = {},
    val onStartOnBootChanged: (Boolean) -> Unit,
    val onOpenBootSettings: () -> Unit = {},
    val onOpenVendorAutostart: () -> Unit = {},
    val onBiometricChanged: (Boolean) -> Unit,
    val onSaveBackupPin: (String) -> Unit,
    val onResetSettings: () -> Unit = {},
    val onCommunityApiUrlChanged: (String) -> Unit = {},
    val onClearCommunityCache: () -> Unit = {},
)

private fun launchSettingsIntents(
    context: Context,
    intents: List<android.content.Intent>,
) {
    val opened =
        intents.any { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    if (!opened) Toast.makeText(context, R.string.settings_system_screen_unavailable, Toast.LENGTH_LONG).show()
}

private fun previewActions(): SettingsScreenActions =
    SettingsScreenActions(
        onOpenAdvancedSettings = {},
        onOpenCustomization = {},
        onOpenAbout = {},
        onOpenDataTransparency = {},
        onOpenDetectionCheck = {},
        onShareDebugBundle = {},
        onRepairPermission = {},
        onOpenVpnPermissionDialog = {},
        onThemeSelected = {},
        onPersonaSelected = {},
        onWebRtcProtectionChanged = {},
        onExcludeRussianAppsChanged = {},
        onFullTunnelModeChanged = {},
        onStartOnBootChanged = {},
        onBiometricChanged = {},
        onSaveBackupPin = {},
        onOpenBackupRestore = {},
        onOpenLogs = {},
    )

@Preview(showBackground = true)
@Composable
private fun previewSettingsScreen() {
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

@Preview(showBackground = true)
@Composable
private fun previewSettingsScreenDark() {
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
