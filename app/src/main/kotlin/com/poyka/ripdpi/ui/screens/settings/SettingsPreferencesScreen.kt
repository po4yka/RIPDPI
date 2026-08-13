package com.poyka.ripdpi.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DnsUiState
import com.poyka.ripdpi.activities.SettingsEffect
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.platform.StartOnBootController
import com.poyka.ripdpi.ui.components.LifecycleEventEffect
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.xray.XrayProviderSettingsStatusRow
import com.poyka.ripdpi.ui.state.SettingsUiState
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Suppress("LongParameterList")
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
    onOpenDomainBypass: () -> Unit = {},
    onOpenRoutingRules: () -> Unit = {},
    onOpenSplitTunnel: () -> Unit = {},
    onOpenRootModeStrategies: () -> Unit = {},
    onOpenSubscriptionFailover: () -> Unit = {},
    onOpenSubscriptionStatus: () -> Unit = {},
    onOpenBackupRestore: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onDismissBackgroundGuidance: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val xrayProviderSnapshot = viewModel.xrayProviderSnapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startOnBootController =
        remember(context) { StartOnBootController(context.applicationContext) }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(startOnBootController.isBatteryOptimizationIgnored())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, startOnBootController) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    batteryOptimizationIgnored = startOnBootController.isBatteryOptimizationIgnored()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LifecycleEventEffect(viewModel.effects) { effect ->
        if (effect is SettingsEffect.RequestDisableBatteryOptimization) {
            launchBatteryOptimizationExemption(context, startOnBootController)
            batteryOptimizationIgnored = startOnBootController.isBatteryOptimizationIgnored()
        }
    }
    SettingsScreen(
        uiState = uiState.value,
        xrayProviderSnapshot = xrayProviderSnapshot.value,
        batteryOptimizationIgnored = batteryOptimizationIgnored,
        actions =
            SettingsScreenActions(
                onOpenDnsSettings = onOpenDnsSettings,
                onOpenAdvancedSettings = onOpenAdvancedSettings,
                onOpenCustomization = onOpenCustomization,
                onOpenAbout = onOpenAbout,
                onOpenDataTransparency = onOpenDataTransparency,
                onOpenDetectionCheck = onOpenDetectionCheck,
                onOpenSubscriptionFailover = onOpenSubscriptionFailover,
                onOpenSubscriptionStatus = onOpenSubscriptionStatus,
                onOpenDomainBypass = onOpenDomainBypass,
                onOpenRoutingRules = onOpenRoutingRules,
                onOpenSplitTunnel = onOpenSplitTunnel,
                onOpenRootModeStrategies = onOpenRootModeStrategies,
                onOpenBackupRestore = onOpenBackupRestore,
                onOpenLogs = onOpenLogs,
                onShareDebugBundle = onShareDebugBundle,
                onRepairPermission = onRepairPermission,
                onOpenVpnPermissionDialog = onOpenVpnPermissionDialog,
                onDismissBackgroundGuidance = onDismissBackgroundGuidance,
                onThemeSelected = remember(viewModel) { viewModel::setAppTheme },
                onPersonaSelected = remember(viewModel) { viewModel::setUiPersona },
                onWebRtcProtectionChanged = remember(viewModel) { viewModel::setWebRtcProtectionEnabled },
                onExcludeRussianAppsChanged = remember(viewModel) { viewModel::setExcludeRussianAppsEnabled },
                onFullTunnelModeChanged = remember(viewModel) { viewModel::setFullTunnelMode },
                onStartOnBootChanged = remember(viewModel) { viewModel::setStartOnBootEnabled },
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
    xrayProviderSnapshot: XrayProviderSnapshot? = null,
    batteryOptimizationIgnored: Boolean = true,
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
        // Embedded-Xray provider status — provider-distinct, rendered only while
        // an Xray session is active (snapshot non-null).
        if (xrayProviderSnapshot != null) {
            item { XrayProviderSettingsStatusRow(snapshot = xrayProviderSnapshot) }
        }
        item { SettingsConnectivitySection(uiState = uiState, actions = actions) }
        item {
            SettingsSecuritySection(
                uiState = uiState,
                localState = localState,
                actions = actions,
                batteryOptimizationIgnored = batteryOptimizationIgnored,
            )
        }
        item { SettingsAppearanceSection(uiState = uiState, actions = actions) }
        item { SettingsPermissionsSection(permissionSummary = permissionSummary, actions = actions) }
        item {
            SettingsSupportSection(
                communityApiUrlDraft = localState.communityApiUrlDraft,
                communityApiUrlReplacementKey = localState.communityApiUrlReplacementKey,
                appVersionName = BuildConfig.VERSION_NAME,
                actions = actions,
                onCommunityApiUrlDraftChanged = localState.onCommunityApiUrlDraftChanged,
                onCommunityApiUrlDraftReset = localState.onCommunityApiUrlDraftReset,
            )
        }
        item { SettingsBackupSection(actions = actions) }
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
    val onOpenSubscriptionFailover: () -> Unit = {},
    val onOpenSubscriptionStatus: () -> Unit = {},
    val onOpenDomainBypass: () -> Unit = {},
    val onOpenRoutingRules: () -> Unit = {},
    val onOpenSplitTunnel: () -> Unit = {},
    val onOpenRootModeStrategies: () -> Unit = {},
    val onOpenBackupRestore: () -> Unit = {},
    val onOpenLogs: () -> Unit = {},
    val onShareDebugBundle: () -> Unit,
    val onRepairPermission: (PermissionKind) -> Unit,
    val onOpenVpnPermissionDialog: () -> Unit,
    val onDismissBackgroundGuidance: () -> Unit = {},
    val onThemeSelected: (String) -> Unit,
    val onPersonaSelected: (String) -> Unit,
    val onWebRtcProtectionChanged: (Boolean) -> Unit,
    val onExcludeRussianAppsChanged: (Boolean) -> Unit,
    val onFullTunnelModeChanged: (Boolean) -> Unit,
    val onStartOnBootChanged: (Boolean) -> Unit,
    val onBiometricChanged: (Boolean) -> Unit,
    val onSaveBackupPin: (String) -> Unit,
    val onResetSettings: () -> Unit = {},
    val onCommunityApiUrlChanged: (String) -> Unit = {},
    val onClearCommunityCache: () -> Unit = {},
)

private fun launchBatteryOptimizationExemption(
    context: Context,
    controller: StartOnBootController,
) {
    val launched =
        controller.batteryOptimizationIntents().any { intent ->
            runCatching { context.startActivity(intent) }.isSuccess
        }
    if (!launched) {
        Logger.withTag("StartOnBoot").w {
            "no battery-optimization exemption activity resolved; relying on warning banner"
        }
    }
}

private fun previewActions(): SettingsScreenActions =
    SettingsScreenActions(
        onOpenDnsSettings = {},
        onOpenAdvancedSettings = {},
        onOpenCustomization = {},
        onOpenAbout = {},
        onOpenDataTransparency = {},
        onOpenDetectionCheck = {},
        onOpenSubscriptionFailover = {},
        onOpenSubscriptionStatus = {},
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
