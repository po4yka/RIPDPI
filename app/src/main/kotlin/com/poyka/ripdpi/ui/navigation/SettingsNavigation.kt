package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.navigation.LocalShownBesideList
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckRoute
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserRoute
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionCheckRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionSettingsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.PcapCaptureListRoute
import com.poyka.ripdpi.ui.screens.diagnostics.PcapViewerRoute
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayHistoryRoute
import com.poyka.ripdpi.ui.screens.diagnostics.share.SharedResultRenderRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.routes.RoutesRoute
import com.poyka.ripdpi.ui.screens.routes.RuleEditorRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.AssetProviderRoute
import com.poyka.ripdpi.ui.screens.settings.BackupRestoreRoute
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyRoute
import com.poyka.ripdpi.ui.screens.settings.DomainBypassListRoute
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworksRoute
import com.poyka.ripdpi.ui.screens.settings.RootModeStrategiesRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsDetailPlaceholder
import com.poyka.ripdpi.ui.screens.settings.SettingsViewModel
import com.poyka.ripdpi.ui.screens.settings.SplitTunnelRoute
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigRoute
import java.io.File

internal fun NavGraphBuilder.addSettingsRoutes(
    navController: NavHostController,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
) {
    navigation<SettingsGraph>(
        startDestination = Route.Settings,
    ) {
        composable<Route.Settings>(
            deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://settings" }),
        ) {
            // Look the graph up on first composition: leaving Settings from the placeholder ends the list-detail
            // pair, and the exiting entry then renders the list after the graph has left the back stack.
            val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
            if (LocalShownBesideList.current) {
                SettingsDetailPlaceholder()
            } else {
                SettingsHomeRoute(navController, settingsGraphEntry, actions, mainViewModel, permissionSummary)
            }
        }
        addAdvancedSettingsRoutes(navController, mainViewModel)
        addDetectionSettingsRoutes(navController)
    }
}

private fun NavGraphBuilder.addAdvancedSettingsRoutes(
    navController: NavHostController,
    mainViewModel: MainViewModel,
) {
    composable<Route.ProfileUtility> {
        com.poyka.ripdpi.ui.screens.profiles
            .ProfileUtilityRoute(
                onBack = { navController.popBackStack() },
                // VPN config holds the paste-link, scan-QR and add-profile entries.
                onImportProfile = { navController.navigateConfigSubRoute(Route.VpnConfig) },
            )
    }
    composable<Route.DnsSettings> {
        val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
        val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
        DnsSettingsRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
    }
    composable<Route.AdvancedSettings> {
        val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
        val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
        AdvancedSettingsRoute(
            onBack = { navController.popBackStack() },
            onOpenStrategyConfig = { navController.navigate(Route.StrategyConfig) },
            onOpenBlockcheck = { navController.navigate(Route.Blockcheck) },
            onOpenAssetProvider = { navController.navigate(Route.AssetProvider) },
            onOpenRememberedNetworks = { navController.navigate(Route.RememberedNetworks) },
            viewModel = settingsViewModel,
        )
    }
    composable<Route.RememberedNetworks> {
        RememberedNetworksRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.RootModeStrategies> {
        RootModeStrategiesRoute(
            onBack = { navController.popBackStack() },
            onOpenStrategyConfig = { navController.navigate(Route.StrategyConfig) },
        )
    }
    composable<Route.StrategyConfig> {
        val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
        val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
        StrategyConfigRoute(
            onBack = { navController.popBackStack() },
            viewModel = settingsViewModel,
            applySavedConfig = mainViewModel::applySavedStrategyConfig,
        )
    }
    composable<Route.Blockcheck> {
        BlockcheckRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.DomainBypassList> {
        DomainBypassListRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.AssetProvider> {
        AssetProviderRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.BackupRestore> {
        BackupRestoreRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.SplitTunnel> {
        SplitTunnelRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.Routes> {
        RoutesRoute(
            onBack = { navController.popBackStack() },
            onAddRule = { navController.navigate(Route.RuleEditor(0L)) },
            onEditRule = { id -> navController.navigate(Route.RuleEditor(id)) },
        )
    }
    composable<Route.RuleEditor> {
        RuleEditorRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.AppCustomization> {
        val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
        val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
        AppCustomizationRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
    }
}

private fun NavGraphBuilder.addDetectionSettingsRoutes(navController: NavHostController) {
    composable<Route.DataTransparency> {
        DataTransparencyRoute(
            onBack = { navController.popBackStack() },
            onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
        )
    }
    composable<Route.DetectionCheck> {
        DetectionCheckRoute(
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(Route.DetectionSettings) },
            onOpenRecommendation = { destination ->
                navController.navigate(destination.toRoute()) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }
    composable<Route.DetectionSettings> {
        DetectionSettingsRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.PcapViewer> { backStackEntry ->
        PcapViewerRoute(
            fileName = backStackEntry.toRoute<Route.PcapViewer>().fileName,
            onBack = { navController.popBackStack() },
        )
    }
    composable<Route.PcapCaptureList> {
        PcapCaptureListRoute(
            onCaptureSelected = { capture ->
                navController.navigate(Route.PcapViewer(File(capture.path).name)) { launchSingleTop = true }
            },
            onBack = { navController.navigateUp() },
            onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
        )
    }
    composable<Route.ReplayHistory> { backStackEntry ->
        ReplayHistoryRoute(
            sessionId = backStackEntry.id,
            onRunScan = { navController.navigate(Route.Diagnostics(autoStartScan = true)) },
            onBack = { navController.navigateUp() },
        )
    }
    composable<Route.OwnedStackBrowser> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.OwnedStackBrowser>()
        OwnedStackBrowserRoute(
            initialUrl = route.initialUrl,
            onBack = { navController.popBackStack() },
        )
    }
    composable<Route.SharedDiagnosticResult> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.SharedDiagnosticResult>()
        SharedResultRenderRoute(
            fragment = route.fragment,
            onBack = { navController.popBackStack() },
        )
    }
    addImportRoutes(navController)
    addReplayRoutes(navController)
}
