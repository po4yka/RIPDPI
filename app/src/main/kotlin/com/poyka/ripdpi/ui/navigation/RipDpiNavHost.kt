package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.activities.HomePauseViewModel
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.PcapCaptureViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.screens.blockcheck.BlockcheckRoute
import com.poyka.ripdpi.ui.screens.browser.OwnedStackBrowserRoute
import com.poyka.ripdpi.ui.screens.config.ConfigModeSection
import com.poyka.ripdpi.ui.screens.config.ConfigRoute
import com.poyka.ripdpi.ui.screens.config.ModeEditorRoute
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionCheckRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionSettingsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRouteCallbacks
import com.poyka.ripdpi.ui.screens.diagnostics.PcapCaptureListRoute
import com.poyka.ripdpi.ui.screens.diagnostics.PcapViewerRoute
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayHistoryRoute
import com.poyka.ripdpi.ui.screens.diagnostics.share.SharedResultRenderRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.health.ConnectionHealthRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.routes.RoutesRoute
import com.poyka.ripdpi.ui.screens.routes.RuleEditorRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.AssetProviderRoute
import com.poyka.ripdpi.ui.screens.settings.BackupRestoreRoute
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyRoute
import com.poyka.ripdpi.ui.screens.settings.DomainBypassListRoute
import com.poyka.ripdpi.ui.screens.settings.RememberedNetworksRoute
import com.poyka.ripdpi.ui.screens.settings.RootModeStrategiesRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
import com.poyka.ripdpi.ui.screens.settings.SplitTunnelRoute
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionFailoverRoute
import com.poyka.ripdpi.ui.screens.subscription.SubscriptionStatusRoute
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerRoute
import com.poyka.ripdpi.ui.screens.tuner.StrategyTunerTopBarAction
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.serialization.Serializable
import java.io.File

private const val DeepLinkScheme = "ripdpi"

/**
 * Marker used by [NavGraphBuilder.navigation] so the Config graph has a type-safe key that
 * [NavHostController.getBackStackEntry] can match. Declared here (rather than in [Route])
 * because graphs are wrappers, not destinations that ever appear in the back stack as leaves.
 */
@Serializable
internal data object ConfigGraph

@Serializable
internal data object SettingsGraph

data class RipDpiNavHostActions(
    val onSaveLogs: () -> Unit = {},
    val onShareDebugBundle: () -> Unit = {},
    val onPrepareDiagnosticsExport: (com.poyka.ripdpi.diagnostics.export.DiagnosticsExportPreparation) -> Unit = {},
    val onShareText: (String, String) -> Unit = { _, _ -> },
    val onRepairPermission: (PermissionKind) -> Unit = {},
)

data class RipDpiNavHostLaunchRequests(
    val launchHomeRequested: Boolean = false,
    val onLaunchHomeHandled: () -> Unit = {},
    val launchRouteRequested: String? = null,
    val onLaunchRouteHandled: () -> Unit = {},
    val sharedDiagnosticFragmentRequested: String? = null,
    val onSharedDiagnosticFragmentHandled: () -> Unit = {},
    val importRouteRequested: Route? = null,
    val onImportRouteHandled: () -> Unit = {},
    val relockRequested: Boolean = false,
    val onRelockHandled: () -> Unit = {},
)

@Suppress("detekt.LongMethod")
@Composable
fun RipDpiNavHost(
    mainViewModel: MainViewModel,
    pauseViewModel: HomePauseViewModel,
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Home,
    actions: RipDpiNavHostActions = RipDpiNavHostActions(),
    launchRequests: RipDpiNavHostLaunchRequests = RipDpiNavHostLaunchRequests(),
    snackbarHostState: SnackbarHostState? = null,
    onExportEligibilityChanged: (Boolean) -> Unit = {},
    exportPreviewContent: @Composable () -> Unit = {},
) {
    val navController = rememberNavController()
    val diagnosticsInitialSection = rememberSaveable { mutableStateOf<DiagnosticsSection?>(null) }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val currentStableRoute = currentDestination?.stableRouteKey()
    val selectedTopLevel = navController.selectedTopLevelRoute(currentBackStackEntry)
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val permissionSummary by mainViewModel.permissionSummary.collectAsStateWithLifecycle(
        initialValue = remember(mainViewModel) { mainViewModel.uiState.value.permissionSummary },
    )

    HandleLaunchRequests(
        launchRequests = launchRequests,
        currentStableRoute = navController.launchGateRoute(currentBackStackEntry, currentStableRoute),
        navigateHome = { navController.navigateHome() },
        navigateToRoute = { destination ->
            if (Route.topLevel.contains(destination)) {
                navController.navigateTopLevel(destination)
            } else {
                navController.navigate(destination) {
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
        navigateToSharedDiagnostic = { fragment ->
            navController.navigate(Route.SharedDiagnosticResult(fragment = fragment)) {
                launchSingleTop = true
            }
        },
        navigateToImportRoute = { destination ->
            navController.navigate(destination) {
                launchSingleTop = true
            }
        },
        relockToRoute = { destination ->
            navController.navigate(destination) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        },
    )

    com.poyka.ripdpi.activities.ExportPreviewContentGate(
        unlocked =
            currentStableRoute != null &&
                !isNavigationGateRoute(currentStableRoute) && !launchRequests.relockRequested,
        onEligibilityChanged = onExportEligibilityChanged,
        content = exportPreviewContent,
    )

    TopLevelNavigationScaffold(
        selectedRoute = selectedTopLevel,
        onNavigate = { destination -> navController.navigateTopLevel(destination) },
        modifier = modifier,
    ) {
        Scaffold(
            containerColor = RipDpiThemeTokens.colors.background,
            snackbarHost = {
                snackbarHostState?.let { hostState ->
                    RipDpiSnackbarHost(
                        hostState = hostState,
                        modifier = Modifier.padding(horizontal = layout.horizontalPadding),
                    )
                }
            },
        ) { innerPadding ->
            RipDpiNavGraph(
                startDestination = startDestination,
                innerPadding = innerPadding,
                navController = navController,
                motion = motion,
                actions = actions,
                mainViewModel = mainViewModel,
                pauseViewModel = pauseViewModel,
                permissionSummary = permissionSummary,
                diagnosticsInitialSection = diagnosticsInitialSection.value,
                onDiagnosticsInitialSectionChanged = { diagnosticsInitialSection.value = it },
            )
        }
    }
}

/** No tab is selected, so no bottom bar or rail shows, while onboarding is on the back stack. */
@Composable
private fun NavHostController.selectedTopLevelRoute(entry: NavBackStackEntry?): Route? =
    if (hasOnboardingEntry(entry)) null else entry?.destination?.topLevelRoute()

private fun NavDestination.topLevelRoute(): Route? =
    Route.topLevel.firstOrNull { matchesRoute(it) }
        ?: when (stableRouteKey()) {
            in configSubRouteStableKeys -> Route.Config
            else -> null
        }

@Composable
private fun HandleLaunchRequests(
    launchRequests: RipDpiNavHostLaunchRequests,
    currentStableRoute: String?,
    navigateHome: () -> Unit,
    navigateToRoute: (Route) -> Unit,
    navigateToSharedDiagnostic: (String) -> Unit,
    navigateToImportRoute: (Route) -> Unit,
    relockToRoute: (Route) -> Unit,
) {
    LaunchedEffect(launchRequests.launchHomeRequested, launchRequests.relockRequested, currentStableRoute) {
        if (!launchRequests.launchHomeRequested ||
            shouldDeferLaunchRequest(currentStableRoute, launchRequests.relockRequested)
        ) {
            return@LaunchedEffect
        }
        when {
            currentStableRoute == Route.Home.stableRoute -> {
                launchRequests.onLaunchHomeHandled()
            }

            shouldNavigateToHomeFromLaunchRequest(launchRequests.launchHomeRequested, currentStableRoute) -> {
                navigateHome()
                launchRequests.onLaunchHomeHandled()
            }
        }
    }

    LaunchedEffect(launchRequests.launchRouteRequested, launchRequests.relockRequested, currentStableRoute) {
        val requestedStableRoute = launchRequests.launchRouteRequested ?: return@LaunchedEffect
        val resolvedCurrentRoute = currentStableRoute ?: return@LaunchedEffect
        if (shouldDeferLaunchRequest(resolvedCurrentRoute, launchRequests.relockRequested)) return@LaunchedEffect
        if (requestedStableRoute == resolvedCurrentRoute) {
            launchRequests.onLaunchRouteHandled()
            return@LaunchedEffect
        }
        val destination = Route.fromStableRoute(requestedStableRoute) ?: return@LaunchedEffect
        navigateToRoute(destination)
        launchRequests.onLaunchRouteHandled()
    }

    LaunchedEffect(
        launchRequests.sharedDiagnosticFragmentRequested,
        launchRequests.relockRequested,
        currentStableRoute,
    ) {
        val fragment = launchRequests.sharedDiagnosticFragmentRequested ?: return@LaunchedEffect
        if (shouldDeferLaunchRequest(currentStableRoute, launchRequests.relockRequested)) {
            return@LaunchedEffect
        }
        navigateToSharedDiagnostic(fragment)
        launchRequests.onSharedDiagnosticFragmentHandled()
    }

    LaunchedEffect(launchRequests.importRouteRequested, launchRequests.relockRequested, currentStableRoute) {
        val destination = launchRequests.importRouteRequested ?: return@LaunchedEffect
        if (shouldDeferLaunchRequest(currentStableRoute, launchRequests.relockRequested)) {
            return@LaunchedEffect
        }
        navigateToImportRoute(destination)
        launchRequests.onImportRouteHandled()
    }

    LaunchedEffect(launchRequests.relockRequested) {
        if (launchRequests.relockRequested) {
            relockToRoute(Route.BiometricPrompt)
            launchRequests.onRelockHandled()
        }
    }
}

@Composable
private fun RipDpiNavGraph(
    startDestination: Route,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    navController: NavHostController,
    motion: RipDpiMotion,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    pauseViewModel: HomePauseViewModel,
    permissionSummary: PermissionSummaryUiState,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier =
            Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        enterTransition = { motion.routeEnterTransition() },
        exitTransition = { motion.routeExitTransition() },
        popEnterTransition = { motion.routePopEnterTransition() },
        popExitTransition = { motion.routePopExitTransition() },
    ) {
        addPrimaryRoutes(
            navController = navController,
            actions = actions,
            mainViewModel = mainViewModel,
            pauseViewModel = pauseViewModel,
            diagnosticsInitialSection = diagnosticsInitialSection,
            onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
        )
        addConfigRoutes(navController = navController, mainViewModel = mainViewModel)
        addSettingsRoutes(
            navController = navController,
            actions = actions,
            mainViewModel = mainViewModel,
            permissionSummary = permissionSummary,
        )
        composable<Route.About> {
            AboutRoute(onBack = { navController.popBackStack() })
        }
    }
}

private fun NavGraphBuilder.addPrimaryRoutes(
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

private fun NavGraphBuilder.addConfigRoutes(
    navController: NavHostController,
    mainViewModel: MainViewModel,
) {
    navigation<ConfigGraph>(
        startDestination = Route.Config,
    ) {
        composable<Route.Config>(
            deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://config" }),
        ) {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                runtimeIntegration = configRuntimeIntegration(mainViewModel),
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenLocalBypassEditor = { navController.navigate(Route.StrategyConfig) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                onRetestStrategies = { navController.navigate(Route.Diagnostics(autoStartScan = true)) },
                onScanServer = { navController.navigate(Route.QrScanner) },
                onOpenProfileEditor = { destination -> navController.navigate(destination) },
                route = Route.Config,
                initialModeSection = ConfigModeSection.LocalBypass,
                viewModel = configViewModel,
                onImport = navController::navigateImport,
                onProfileShare = { profileId -> navController.navigate(Route.ProfileShare(profileId = profileId)) },
            )
        }
        composable<Route.LocalBypassConfig> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                runtimeIntegration = configRuntimeIntegration(mainViewModel),
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenLocalBypassEditor = { navController.navigate(Route.StrategyConfig) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                onRetestStrategies = { navController.navigate(Route.Diagnostics(autoStartScan = true)) },
                onScanServer = { navController.navigate(Route.QrScanner) },
                onOpenProfileEditor = { destination -> navController.navigate(destination) },
                route = Route.LocalBypassConfig,
                initialModeSection = ConfigModeSection.LocalBypass,
                viewModel = configViewModel,
                onImport = navController::navigateImport,
                onProfileShare = { profileId -> navController.navigate(Route.ProfileShare(profileId = profileId)) },
            )
        }
        composable<Route.VpnConfig> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                runtimeIntegration = configRuntimeIntegration(mainViewModel),
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenLocalBypassEditor = { navController.navigate(Route.StrategyConfig) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                onRetestStrategies = { navController.navigate(Route.Diagnostics(autoStartScan = true)) },
                onScanServer = { navController.navigate(Route.QrScanner) },
                onOpenProfileEditor = { destination -> navController.navigate(destination) },
                route = Route.VpnConfig,
                initialModeSection = ConfigModeSection.Vpn,
                viewModel = configViewModel,
                onImport = navController::navigateImport,
                onProfileShare = { profileId -> navController.navigate(Route.ProfileShare(profileId = profileId)) },
            )
        }
        composable<Route.ModeEditor> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ModeEditorRoute(
                onBack = { navController.popBackStack() },
                onOpenXrayImport = { navController.navigate(Route.XrayImport) { launchSingleTop = true } },
                viewModel = configViewModel,
            )
        }
    }
}

private fun NavGraphBuilder.addSettingsRoutes(
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
            SettingsHomeRoute(navController, it, actions, mainViewModel, permissionSummary)
        }
        addAdvancedSettingsRoutes(navController, mainViewModel)
        addDetectionSettingsRoutes(navController)
    }
}

@Composable
private fun SettingsHomeRoute(
    navController: NavHostController,
    backStackEntry: NavBackStackEntry,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    permissionSummary: PermissionSummaryUiState,
) {
    val settingsGraphEntry =
        remember(navController, backStackEntry) {
            navController.getBackStackEntry<SettingsGraph>()
        }
    val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
    SettingsRoute(
        onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
        onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
        onOpenCustomization = { navController.navigate(Route.AppCustomization) },
        onOpenAbout = { navController.navigate(Route.About) },
        onOpenDataTransparency = { navController.navigate(Route.DataTransparency) },
        onOpenDetectionCheck = { navController.navigate(Route.DetectionCheck) },
        onOpenSubscriptionFailover = { navController.navigate(Route.SubscriptionFailover) },
        onOpenSubscriptionStatus = { navController.navigate(Route.SubscriptionStatus) },
        onOpenProfileUtility = { navController.navigate(Route.ProfileUtility) },
        onOpenDomainBypass = { navController.navigate(Route.DomainBypassList) },
        onOpenRoutingRules = { navController.navigate(Route.Routes) },
        onOpenSplitTunnel = { navController.navigate(Route.SplitTunnel) },
        onOpenRootModeStrategies = { navController.navigate(Route.RootModeStrategies) },
        onOpenBackupRestore = { navController.navigate(Route.BackupRestore) },
        onOpenLogs = { navController.navigate(Route.Logs) { launchSingleTop = true } },
        onShareDebugBundle = actions.onShareDebugBundle,
        permissionSummary = permissionSummary,
        onRepairPermission = actions.onRepairPermission,
        onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
        onDismissBackgroundGuidance = mainViewModel::onDismissBackgroundGuidance,
        viewModel = settingsViewModel,
    )
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

// Callers leave a finished flow (an import, a launch request), so its screens are dropped,
// not saved. The tab save/restore options are ignored when a plainly pushed screen is on top.
internal fun NavHostController.navigateHome() {
    navigate(Route.Home) {
        launchSingleTop = true
        popUpTo<Route.Home>()
    }
}

private fun NavHostController.navigateTopLevel(destination: Route) {
    navigate(destination) {
        launchSingleTop = true
        restoreState = true
        popUpTo<Route.Home> { saveState = true }
    }
}

private fun NavHostController.navigateConfigSubRoute(destination: Route) {
    navigate(destination) {
        launchSingleTop = true
        restoreState = true
        popUpTo<Route.Home> { saveState = true }
    }
}

internal fun nestedEnterTransition(motion: RipDpiMotion) = motion.nestedEnterTransition()

internal fun nestedPopExitTransition(motion: RipDpiMotion) = motion.nestedPopExitTransition()

/**
 * Returns the stable route key (e.g. "home") for a Nav destination.
 *
 * Needed because Navigation Compose 2.8+ typed routes expose `destination.route` as a
 * fully-qualified class name, not the stable key that [TopLevelNavigationScaffold] and the
 * launch-request pipeline still speak. The stable key is preserved on [Route] itself
 * so external surfaces (automation, telemetry, deep links) keep their string contract.
 */
internal fun NavDestination.stableRouteKey(): String? =
    stableRouteMatchers.firstOrNull { (_, matches) -> matches() }?.first

internal fun NavDestination.matchesRoute(route: Route): Boolean = stableRouteKey() == route.stableRoute

// Only variants of the Connection tab itself (the same screen with a preselected section).
// Sub-screens such as editors and Logs show a back arrow and no bottom bar.
private val configSubRouteStableKeys =
    setOf(
        Route.LocalBypassConfig.stableRoute,
        Route.VpnConfig.stableRoute,
    )

private val stableRouteMatchers: List<Pair<String, NavDestination.() -> Boolean>> =
    listOf(
        Route.Home.stableRoute to { hasRoute<Route.Home>() },
        Route.Config.stableRoute to { hasRoute<Route.Config>() },
        Route.LocalBypassConfig.stableRoute to { hasRoute<Route.LocalBypassConfig>() },
        Route.VpnConfig.stableRoute to { hasRoute<Route.VpnConfig>() },
        Route.Diagnostics().stableRoute to { hasRoute<Route.Diagnostics>() },
        Route.Settings.stableRoute to { hasRoute<Route.Settings>() },
        Route.ProfileUtility.stableRoute to { hasRoute<Route.ProfileUtility>() },
        Route.Onboarding.stableRoute to { hasRoute<Route.Onboarding>() },
        Route.History.stableRoute to { hasRoute<Route.History>() },
        Route.Logs.stableRoute to { hasRoute<Route.Logs>() },
        Route.ConnectionHealth.stableRoute to { hasRoute<Route.ConnectionHealth>() },
        Route.SubscriptionFailover.stableRoute to { hasRoute<Route.SubscriptionFailover>() },
        Route.SubscriptionStatus.stableRoute to { hasRoute<Route.SubscriptionStatus>() },
        Route.StrategyTuner.stableRoute to { hasRoute<Route.StrategyTuner>() },
        Route.ModeEditor.stableRoute to { hasRoute<Route.ModeEditor>() },
        Route.BackupRestore.stableRoute to { hasRoute<Route.BackupRestore>() },
        Route.DnsSettings.stableRoute to { hasRoute<Route.DnsSettings>() },
        Route.AdvancedSettings.stableRoute to { hasRoute<Route.AdvancedSettings>() },
        Route.StrategyConfig.stableRoute to { hasRoute<Route.StrategyConfig>() },
        Route.RememberedNetworks.stableRoute to { hasRoute<Route.RememberedNetworks>() },
        Route.RootModeStrategies.stableRoute to { hasRoute<Route.RootModeStrategies>() },
        Route.DomainBypassList.stableRoute to { hasRoute<Route.DomainBypassList>() },
        Route.AssetProvider.stableRoute to { hasRoute<Route.AssetProvider>() },
        Route.SplitTunnel.stableRoute to { hasRoute<Route.SplitTunnel>() },
        Route.Routes.stableRoute to { hasRoute<Route.Routes>() },
        Route.RuleEditor().stableRoute to { hasRoute<Route.RuleEditor>() },
        Route.Blockcheck.stableRoute to { hasRoute<Route.Blockcheck>() },
        Route.BiometricPrompt.stableRoute to { hasRoute<Route.BiometricPrompt>() },
        Route.AppCustomization.stableRoute to { hasRoute<Route.AppCustomization>() },
        Route.About.stableRoute to { hasRoute<Route.About>() },
        Route.DataTransparency.stableRoute to { hasRoute<Route.DataTransparency>() },
        Route.DetectionCheck.stableRoute to { hasRoute<Route.DetectionCheck>() },
        Route.DetectionSettings.stableRoute to { hasRoute<Route.DetectionSettings>() },
        Route.PcapViewer().stableRoute to { hasRoute<Route.PcapViewer>() },
        Route.PcapCaptureList.stableRoute to { hasRoute<Route.PcapCaptureList>() },
        Route.ReplayHistory.stableRoute to { hasRoute<Route.ReplayHistory>() },
        Route.ReplayFailure().stableRoute to { hasRoute<Route.ReplayFailure>() },
        Route.OwnedStackBrowser().stableRoute to { hasRoute<Route.OwnedStackBrowser>() },
        Route.SharedDiagnosticResult().stableRoute to { hasRoute<Route.SharedDiagnosticResult>() },
        Route.ProfileImportConfirm().stableRoute to { hasRoute<Route.ProfileImportConfirm>() },
        Route.SubscriptionImportConfirm().stableRoute to { hasRoute<Route.SubscriptionImportConfirm>() },
        Route.SupportSettings().stableRoute to { hasRoute<Route.SupportSettings>() },
        Route.ProfileShare().stableRoute to { hasRoute<Route.ProfileShare>() },
        Route.QrScanner.stableRoute to { hasRoute<Route.QrScanner>() },
        Route.AmneziaWgProfile().stableRoute to { hasRoute<Route.AmneziaWgProfile>() },
        Route.SubscriptionAdd.stableRoute to { hasRoute<Route.SubscriptionAdd>() },
        Route.XrayImport.stableRoute to { hasRoute<Route.XrayImport>() },
        Route.AnyTlsProfile.stableRoute to { hasRoute<Route.AnyTlsProfile>() },
        Route.MieruProfile.stableRoute to { hasRoute<Route.MieruProfile>() },
        Route.MieruProfileEdit().stableRoute to { hasRoute<Route.MieruProfileEdit>() },
        Route.SshProfile.stableRoute to { hasRoute<Route.SshProfile>() },
        Route.SshProfileEdit().stableRoute to { hasRoute<Route.SshProfileEdit>() },
    )

internal fun shouldNavigateToHomeFromLaunchRequest(
    launchHomeRequested: Boolean,
    currentRoute: String?,
): Boolean {
    if (!launchHomeRequested || currentRoute == null) {
        return false
    }

    return currentRoute != Route.Home.stableRoute && !isNavigationGateRoute(currentRoute)
}

internal fun isNavigationGateRoute(route: String?): Boolean =
    route == Route.Onboarding.stableRoute || route == Route.BiometricPrompt.stableRoute

private fun shouldDeferLaunchRequest(
    currentRoute: String?,
    relockRequested: Boolean,
): Boolean = relockRequested || currentRoute == null || isNavigationGateRoute(currentRoute)
