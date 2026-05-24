package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.screens.awg.AmneziaWgProfileRoute
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
import com.poyka.ripdpi.ui.screens.diagnostics.PcapViewerRoute
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayFailureRoute
import com.poyka.ripdpi.ui.screens.diagnostics.share.SharedResultRenderRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
import com.poyka.ripdpi.ui.screens.onboarding.FirstRunTestRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmRoute
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmRoute
import com.poyka.ripdpi.ui.screens.scanner.QrScannerRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
import com.poyka.ripdpi.ui.screens.settings.StrategyConfigRoute
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.serialization.Serializable

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
    val onSaveDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    val onShareDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    val onShareDiagnosticsSummary: (String, String) -> Unit = { _, _ -> },
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

@Composable
fun RipDpiNavHost(
    modifier: Modifier = Modifier,
    startDestination: Route = Route.Home,
    mainViewModel: MainViewModel,
    actions: RipDpiNavHostActions = RipDpiNavHostActions(),
    launchRequests: RipDpiNavHostLaunchRequests = RipDpiNavHostLaunchRequests(),
    snackbarHostState: SnackbarHostState? = null,
) {
    val navController = rememberNavController()
    val diagnosticsInitialSection = remember { mutableStateOf<DiagnosticsSection?>(null) }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val currentStableRoute = currentDestination?.stableRouteKey()
    val selectedTopLevel =
        currentDestination?.let { destination ->
            Route.topLevel.firstOrNull { destination.matchesRoute(it) }
                ?: if (currentStableRoute in configSubRouteStableKeys) Route.Config else null
        }
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()

    HandleLaunchRequests(
        launchRequests = launchRequests,
        currentStableRoute = currentStableRoute,
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

    val isWideScreen = rememberIsWideScreen()
    Scaffold(
        modifier = modifier,
        containerColor = RipDpiThemeTokens.colors.background,
        snackbarHost = {
            snackbarHostState?.let { hostState ->
                RipDpiSnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding),
                )
            }
        },
        bottomBar = {
            if (!isWideScreen) {
                TopLevelBottomBar(
                    selectedTopLevel = selectedTopLevel,
                    onNavigate = { destination -> navController.navigateTopLevel(destination) },
                )
            }
        },
    ) { innerPadding ->
        ResponsiveNavContent(
            isWideScreen = isWideScreen,
            selectedTopLevel = selectedTopLevel,
            innerPadding = innerPadding,
            navController = navController,
            startDestination = startDestination,
            motion = motion,
            actions = actions,
            mainViewModel = mainViewModel,
            mainUiState = mainUiState,
            diagnosticsInitialSection = diagnosticsInitialSection.value,
            onDiagnosticsInitialSectionChanged = { diagnosticsInitialSection.value = it },
        )
    }
}

@Composable
private fun ResponsiveNavContent(
    isWideScreen: Boolean,
    selectedTopLevel: Route?,
    innerPadding: PaddingValues,
    navController: NavHostController,
    startDestination: Route,
    motion: RipDpiMotion,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    mainUiState: MainUiState,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    if (isWideScreen && selectedTopLevel != null) {
        Row(modifier = Modifier.padding(innerPadding)) {
            RipDpiNavRail(
                selectedRoute = selectedTopLevel,
                onNavigate = { destination -> navController.navigateTopLevel(destination) },
            )
            Box(modifier = Modifier.weight(1f)) {
                RipDpiNavGraph(
                    startDestination = startDestination,
                    innerPadding = PaddingValues(),
                    navController = navController,
                    motion = motion,
                    actions = actions,
                    mainViewModel = mainViewModel,
                    mainUiState = mainUiState,
                    diagnosticsInitialSection = diagnosticsInitialSection,
                    onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
                )
            }
        }
    } else {
        RipDpiNavGraph(
            startDestination = startDestination,
            innerPadding = innerPadding,
            navController = navController,
            motion = motion,
            actions = actions,
            mainViewModel = mainViewModel,
            mainUiState = mainUiState,
            diagnosticsInitialSection = diagnosticsInitialSection,
            onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
        )
    }
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
    LaunchedEffect(launchRequests.launchHomeRequested, currentStableRoute) {
        if (!launchRequests.launchHomeRequested || currentStableRoute == null) {
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

    LaunchedEffect(launchRequests.launchRouteRequested, currentStableRoute) {
        val requestedStableRoute = launchRequests.launchRouteRequested ?: return@LaunchedEffect
        val resolvedCurrentRoute = currentStableRoute ?: return@LaunchedEffect
        if (requestedStableRoute == resolvedCurrentRoute) {
            launchRequests.onLaunchRouteHandled()
            return@LaunchedEffect
        }
        val destination = Route.fromStableRoute(requestedStableRoute) ?: return@LaunchedEffect
        navigateToRoute(destination)
        launchRequests.onLaunchRouteHandled()
    }

    LaunchedEffect(launchRequests.sharedDiagnosticFragmentRequested, currentStableRoute) {
        val fragment = launchRequests.sharedDiagnosticFragmentRequested ?: return@LaunchedEffect
        if (currentStableRoute == null) {
            return@LaunchedEffect
        }
        navigateToSharedDiagnostic(fragment)
        launchRequests.onSharedDiagnosticFragmentHandled()
    }

    LaunchedEffect(launchRequests.importRouteRequested, currentStableRoute) {
        val destination = launchRequests.importRouteRequested ?: return@LaunchedEffect
        if (currentStableRoute == null) {
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
private fun TopLevelBottomBar(
    selectedTopLevel: Route?,
    onNavigate: (Route) -> Unit,
) {
    val destination = selectedTopLevel ?: return
    BottomNavBar(
        selectedRoute = destination,
        onNavigate = onNavigate,
    )
}

@Composable
private fun RipDpiNavGraph(
    startDestination: Route,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    navController: NavHostController,
    motion: RipDpiMotion,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    mainUiState: MainUiState,
    diagnosticsInitialSection: DiagnosticsSection?,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(innerPadding),
        enterTransition = { motion.routeEnterTransition() },
        exitTransition = { motion.routeExitTransition() },
        popEnterTransition = { motion.routeEnterTransition(initialScale = 0.992f) },
        popExitTransition = { motion.routePopExitTransition() },
    ) {
        addPrimaryRoutes(
            navController = navController,
            actions = actions,
            mainViewModel = mainViewModel,
            diagnosticsInitialSection = diagnosticsInitialSection,
            onDiagnosticsInitialSectionChanged = onDiagnosticsInitialSectionChanged,
        )
        addConfigRoutes(navController = navController)
        addSettingsRoutes(
            navController = navController,
            actions = actions,
            mainViewModel = mainViewModel,
            mainUiState = mainUiState,
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
        )
    }
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
            onRunDiagnosticsScan = {
                onDiagnosticsInitialSectionChanged(DiagnosticsSection.Scan)
                navController.navigate(Route.Diagnostics(autoStartScan = true)) {
                    launchSingleTop = true
                }
            },
            onOpenHistory = { navController.navigate(Route.History) { launchSingleTop = true } },
            onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
            onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
            onOpenOwnedStackBrowser = { url -> navController.navigate(Route.OwnedStackBrowser(initialUrl = url)) },
            onOpenLocalBypassConfig = { navController.navigateConfigSubRoute(Route.LocalBypassConfig) },
            onOpenVpnConfig = { navController.navigateConfigSubRoute(Route.VpnConfig) },
            onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
            viewModel = mainViewModel,
        )
    }
    composable<Route.Diagnostics>(
        deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://diagnostics" }),
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<Route.Diagnostics>()
        val diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
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
        )
    }
    composable<Route.History> {
        HistoryRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.Logs> {
        LogsRoute(
            onSaveLogs = actions.onSaveLogs,
            onShareSupportBundle = actions.onShareDebugBundle,
        )
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

private fun diagnosticsRouteCallbacks(
    navController: NavHostController,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    onDiagnosticsInitialSectionChanged: (DiagnosticsSection?) -> Unit,
): DiagnosticsRouteCallbacks =
    DiagnosticsRouteCallbacks(
        onShareArchive = actions.onShareDiagnosticsArchive,
        onSaveArchive = actions.onSaveDiagnosticsArchive,
        onShareSummary = actions.onShareDiagnosticsSummary,
        onSaveLogs = actions.onSaveLogs,
        onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
        onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
        onOpenDetectionCheck = { navController.navigate(Route.DetectionCheck) },
        onRequestVpnPermission = mainViewModel::onOpenVpnPermissionRequested,
        onOpenHistory = { navController.navigate(Route.History) { launchSingleTop = true } },
        onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
        onOpenOwnedStackBrowser = { url -> navController.navigate(Route.OwnedStackBrowser(initialUrl = url)) },
        onInitialSectionHandled = { onDiagnosticsInitialSectionChanged(null) },
    )

private fun NavGraphBuilder.addConfigRoutes(navController: NavHostController) {
    navigation<ConfigGraph>(
        startDestination = Route.Config,
    ) {
        composable<Route.Config>(
            deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://config" }),
        ) {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                initialModeSection = ConfigModeSection.LocalBypass,
                viewModel = configViewModel,
                onProfileImport = { request -> navController.navigateProfileImport(request) },
            )
        }
        composable<Route.LocalBypassConfig> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                initialModeSection = ConfigModeSection.LocalBypass,
                viewModel = configViewModel,
                onProfileImport = { request -> navController.navigateProfileImport(request) },
            )
        }
        composable<Route.VpnConfig> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                initialModeSection = ConfigModeSection.Vpn,
                viewModel = configViewModel,
                onProfileImport = { request -> navController.navigateProfileImport(request) },
            )
        }
        composable<Route.ModeEditor> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ModeEditorRoute(
                onBack = { navController.popBackStack() },
                viewModel = configViewModel,
            )
        }
    }
}

private fun NavGraphBuilder.addSettingsRoutes(
    navController: NavHostController,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    mainUiState: MainUiState,
) {
    navigation<SettingsGraph>(
        startDestination = Route.Settings,
    ) {
        composable<Route.Settings>(
            deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://settings" }),
        ) {
            SettingsHomeRoute(navController, it, actions, mainViewModel, mainUiState)
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
    mainUiState: MainUiState,
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
        onShareDebugBundle = actions.onShareDebugBundle,
        permissionSummary = mainUiState.permissionSummary,
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
            viewModel = settingsViewModel,
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
    composable<Route.AppCustomization> {
        val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
        val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
        AppCustomizationRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
    }
}

private fun NavGraphBuilder.addDetectionSettingsRoutes(navController: NavHostController) {
    composable<Route.DataTransparency> {
        DataTransparencyRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.DetectionCheck> {
        DetectionCheckRoute(
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(Route.DetectionSettings) },
        )
    }
    composable<Route.DetectionSettings> {
        DetectionSettingsRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.FirstRunTest> {
        FirstRunTestRoute(
            onSkip = { navController.popBackStack() },
            onApplyRecommendation = { navController.popBackStack() },
        )
    }
    composable<Route.PcapViewer> {
        PcapViewerRoute(onBack = { navController.popBackStack() })
    }
    composable<Route.ReplayFailure> {
        ReplayFailureRoute(onBack = { navController.popBackStack() })
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
}

private fun NavGraphBuilder.addImportRoutes(navController: NavHostController) {
    composable<Route.ProfileImportConfirm> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.ProfileImportConfirm>()
        val profile = remember(route.profileJson) { decodeImportedProfile(route.profileJson) }
        if (profile == null) {
            // Defensive: a malformed payload should never have reached navigation, but if
            // it does, fall back rather than crash.
            navController.navigateHome()
        } else {
            ProfileImportConfirmRoute(
                profile = profile,
                onBack = { navController.popBackStack() },
                onImported = { navController.navigateHome() },
            )
        }
    }
    composable<Route.SubscriptionImportConfirm> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.SubscriptionImportConfirm>()
        SubscriptionImportConfirmRoute(
            url = route.url,
            name = route.name,
            bootstrap = route.bootstrap,
            onBack = { navController.popBackStack() },
            onImported = { navController.navigateHome() },
        )
    }
    composable<Route.QrScanner> {
        QrScannerRoute(
            onBack = { navController.popBackStack() },
            onProfileScanned = { request ->
                navController.navigate(
                    Route.ProfileImportConfirm(profileJson = encodeImportedProfile(request.profile)),
                ) {
                    launchSingleTop = true
                }
            },
        )
    }
    composable<Route.AmneziaWgProfile> {
        AmneziaWgProfileRoute(onBack = { navController.popBackStack() })
    }
}

/**
 * Routes to the single-profile import-confirmation destination for a clipboard- or
 * scanner-sourced [request], encoding the parsed profile into the type-safe route arg.
 */
private fun NavHostController.navigateProfileImport(request: com.poyka.ripdpi.proxyimport.ProxyImportRequest.Profile) {
    navigate(Route.ProfileImportConfirm(profileJson = encodeImportedProfile(request.profile))) {
        launchSingleTop = true
    }
}

private val importProfileJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal fun decodeImportedProfile(profileJson: String): com.poyka.ripdpi.data.ProxyProfile? =
    profileJson
        .takeIf { it.isNotBlank() }
        ?.let { json ->
            runCatching {
                importProfileJson.decodeFromString(
                    com.poyka.ripdpi.data.ProxyProfile
                        .serializer(),
                    json,
                )
            }.getOrNull()
        }

internal fun encodeImportedProfile(profile: com.poyka.ripdpi.data.ProxyProfile): String =
    importProfileJson.encodeToString(
        com.poyka.ripdpi.data.ProxyProfile
            .serializer(),
        profile,
    )

private fun NavHostController.navigateHome() {
    navigateTopLevel(Route.Home)
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
 * fully-qualified class name, not the stable key that [BottomNavBar] and the
 * launch-request pipeline still speak. The stable key is preserved on [Route] itself
 * so external surfaces (automation, telemetry, deep links) keep their string contract.
 */
internal fun NavDestination.stableRouteKey(): String? =
    stableRouteMatchers.firstOrNull { (_, matches) -> matches() }?.first

internal fun NavDestination.matchesRoute(route: Route): Boolean = stableRouteKey() == route.stableRoute

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
        Route.Onboarding.stableRoute to { hasRoute<Route.Onboarding>() },
        Route.History.stableRoute to { hasRoute<Route.History>() },
        Route.Logs.stableRoute to { hasRoute<Route.Logs>() },
        Route.ModeEditor.stableRoute to { hasRoute<Route.ModeEditor>() },
        Route.DnsSettings.stableRoute to { hasRoute<Route.DnsSettings>() },
        Route.AdvancedSettings.stableRoute to { hasRoute<Route.AdvancedSettings>() },
        Route.BiometricPrompt.stableRoute to { hasRoute<Route.BiometricPrompt>() },
        Route.AppCustomization.stableRoute to { hasRoute<Route.AppCustomization>() },
        Route.About.stableRoute to { hasRoute<Route.About>() },
        Route.DataTransparency.stableRoute to { hasRoute<Route.DataTransparency>() },
        Route.DetectionCheck.stableRoute to { hasRoute<Route.DetectionCheck>() },
        Route.DetectionSettings.stableRoute to { hasRoute<Route.DetectionSettings>() },
        Route.OwnedStackBrowser().stableRoute to { hasRoute<Route.OwnedStackBrowser>() },
        Route.SharedDiagnosticResult().stableRoute to { hasRoute<Route.SharedDiagnosticResult>() },
        Route.ProfileImportConfirm().stableRoute to { hasRoute<Route.ProfileImportConfirm>() },
        Route.SubscriptionImportConfirm().stableRoute to { hasRoute<Route.SubscriptionImportConfirm>() },
        Route.QrScanner.stableRoute to { hasRoute<Route.QrScanner>() },
        Route.AmneziaWgProfile.stableRoute to { hasRoute<Route.AmneziaWgProfile>() },
    )

internal fun shouldNavigateToHomeFromLaunchRequest(
    launchHomeRequested: Boolean,
    currentRoute: String?,
): Boolean {
    if (!launchHomeRequested || currentRoute == null) {
        return false
    }

    return currentRoute != Route.Home.stableRoute &&
        currentRoute !in
        setOf(
            Route.Onboarding.stableRoute,
            Route.BiometricPrompt.stableRoute,
        )
}
