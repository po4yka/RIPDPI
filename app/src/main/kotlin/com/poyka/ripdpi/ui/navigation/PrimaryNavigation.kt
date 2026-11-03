package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.poyka.ripdpi.activities.HomePauseViewModel
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRouteCallbacks
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSection
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsViewModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.PcapCaptureViewModel
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusRoute
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerRoute
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerTopBarAction

internal fun NavGraphBuilder.addPrimaryRoutes(
    navController: NavHostController,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    pauseViewModel: HomePauseViewModel,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    composable<Route.Onboarding> {
        OnboardingRoute(
            onComplete = {
                navController.navigate(Route.Home) {
                    popUpTo<Route.Onboarding> { inclusive = true }
                }
            },
            onOpenAdvancedDns = { navController.navigate(Route.DnsSettings) },
            onOpenXrayImport = { navController.navigate(Route.XrayImport) { launchSingleTop = true } },
        )
    }
    addHomeRoute(navController, mainViewModel, pauseViewModel, onDiagnosticsInitialSectionChanged)
    composable<Route.Diagnostics>(
        deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://diagnostics" }),
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<Route.Diagnostics>()
        val diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
        val pcapCaptureViewModel: PcapCaptureViewModel = hiltViewModel()
        val diagnosticsHomeState by mainViewModel.uiState.collectAsStateWithLifecycle()
        DiagnosticsRoute(
            callbacks =
                diagnosticsRouteCallbacks(
                    navController = navController,
                    actions = actions,
                    mainViewModel = mainViewModel,
                    onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
                ),
            initialSection = diagnosticsInitialSection ?: DiagnosticsSection.Scan.takeIf { route.autoStartScan },
            viewModel = diagnosticsViewModel,
            pcapCaptureViewModel = pcapCaptureViewModel,
            homeDiagnostics = diagnosticsHomeState.homeDiagnostics,
            topBarExtraActions = {
                StrategyTunerTopBarAction(
                    onOpen = { navController.navigate(Route.StrategyTuner) { launchSingleTop = true } },
                )
            },
        )
    }
    composable<Route.History> {
        HistoryRoute(
            onBack = { navController.popBackStack() },
            onOpenConnection = {
                navController.navigateToConnectionFromHistory()
            },
            onOpenDiagnostics = {
                onDiagnosticsInitialSectionChanged(DiagnosticsSection.Scan)
                navController.navigateToDiagnosticsFromHistory()
            },
        )
    }
    composable<Route.Logs> {
        LogsRoute(
            onBack = { navController.popBackStack() },
            onSaveLogs = actions.onSaveLogs,
            onShareSupportBundle = actions.onShareDebugBundle,
        )
    }
    composable<Route.ConnectionHealth> {
        ConnectionHealthRoute(onBack = { navController.popBackStack() })
    }
    addSubscriptionFailoverRoute(navController)
    addSubscriptionStatusRoute(navController)
    composable<Route.StrategyTuner> {
        StrategyTunerRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.BiometricPrompt> {
        BiometricPromptRoute(
            onAuthenticated = {
                mainViewModel.appLock.onAuthenticated()
                navController.navigate(Route.Home) {
                    popUpTo<Route.BiometricPrompt> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

private fun NavGraphBuilder.addHomeRoute(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    pauseViewModel: HomePauseViewModel,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    composable<Route.Home>(
        deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://connect" }),
    ) {
        HomeRoute(
            onOpenDiagnostics = {
                onDiagnosticsInitialSectionChanged(DiagnosticsSection.Scan)
                navController.navigate(Route.Diagnostics()) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onOpenHistory = { navController.navigate(Route.History) { launchSingleTop = true } },
            onOpenConnectionHealth = { navController.navigate(Route.ConnectionHealth) { launchSingleTop = true } },
            onOpenSubscriptionStatus = { navController.navigate(Route.SubscriptionStatus) { launchSingleTop = true } },
            onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
            onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
            onOpenOwnedStackBrowser = { url -> navController.navigate(Route.OwnedStackBrowser(initialUrl = url)) },
            onOpenLocalBypassConfig = { navController.navigateConfigSubRoute(Route.LocalBypassConfig) },
            onOpenVpnConfig = { navController.navigateConfigSubRoute(Route.VpnConfig) },
            onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
            viewModel = mainViewModel,
            pauseViewModel = pauseViewModel,
        )
    }
}

private fun NavGraphBuilder.addSubscriptionFailoverRoute(navController: NavHostController) {
    composable<Route.SubscriptionFailover>(
        deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://subscription-failover" }),
    ) {
        SubscriptionFailoverRoute(
            onBack = { navController.popBackStack() },
            onAddSubscription = { navController.navigate(Route.SubscriptionAdd) { launchSingleTop = true } },
        )
    }
}

private fun NavGraphBuilder.addSubscriptionStatusRoute(navController: NavHostController) {
    composable<Route.SubscriptionStatus> {
        SubscriptionStatusRoute(
            onBack = { navController.popBackStack() },
            onAddSubscription = { navController.navigate(Route.SubscriptionAdd) { launchSingleTop = true } },
        )
    }
}

private fun diagnosticsRouteCallbacks(
    navController: NavHostController,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
): DiagnosticsRouteCallbacks =
    DiagnosticsRouteCallbacks(
        onPrepareExport = actions.onPrepareDiagnosticsExport,
        onShareText = actions.onShareText,
        onSaveLogs = actions.onSaveLogs,
        onOpenLogs = { navController.navigate(Route.Logs) { launchSingleTop = true } },
        onOpenConnectionHealth = { navController.navigate(Route.ConnectionHealth) { launchSingleTop = true } },
        onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
        onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
        onOpenDetectionCheck = { navController.navigate(Route.DetectionCheck) },
        onRequestVpnPermission = mainViewModel::onOpenVpnPermissionRequested,
        onRequestLocalNetworkPermission = { mainViewModel.onRepairPermissionRequested(PermissionKind.LocalNetwork) },
        onOpenHistory = { navController.navigate(Route.History) { launchSingleTop = true } },
        onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
        onOpenOwnedStackBrowser = { url -> navController.navigate(Route.OwnedStackBrowser(initialUrl = url)) },
        onOpenPcapCaptureList = { navController.navigate(Route.PcapCaptureList) },
        onOpenPastReplays = { navController.navigate(Route.ReplayHistory) },
        onReplayProbe = { route -> navController.navigate(route) },
        onInitialSectionHandled = { onDiagnosticsInitialSectionChanged(null) },
        onCancelHomeAnalysis = mainViewModel::onCancelHomeAnalysis,
        onCheckNetwork = mainViewModel::onRunHomeQuickAnalysis,
        onStartVerifiedVpn = mainViewModel::onStartVerifiedVpn,
        onDismissVerification = mainViewModel.dismissHomeVerificationSheet,
    )
