package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSummaryUiState
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.components.navigation.RipDpiListDetailLayout
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsSection
import com.poyka.ripdpi.ui.screens.settings.LocalSettingsListState
import com.poyka.ripdpi.ui.screens.status.StatusPauseViewModel
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.serialization.Serializable

internal const val DeepLinkScheme = "ripdpi"

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
    pauseViewModel: StatusPauseViewModel,
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
    val selectedTopLevel =
        rememberHeldTopLevel(navController.selectedTopLevelRoute(currentBackStackEntry), navController)
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
            RipDpiNavPanes(
                navController = navController,
                startDestination = startDestination,
                motion = motion,
                actions = actions,
                mainViewModel = mainViewModel,
                pauseViewModel = pauseViewModel,
                permissionSummary = permissionSummary,
                diagnosticsInitialSection = diagnosticsInitialSection,
                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            )
        }
    }
}

/** The NavHost, with the Settings list beside it while a Settings list-detail pair is current on a wide window. */
@Composable
private fun RipDpiNavPanes(
    navController: NavHostController,
    startDestination: Route,
    motion: RipDpiMotion,
    actions: RipDpiNavHostActions,
    mainViewModel: MainViewModel,
    pauseViewModel: StatusPauseViewModel,
    permissionSummary: PermissionSummaryUiState,
    diagnosticsInitialSection: MutableState<DiagnosticsSection?>,
    modifier: Modifier = Modifier,
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val settingsListDetail = navController.settingsListDetailFor(currentBackStackEntry)
    val settingsListState = rememberLazyListState()
    // Always laid out through the list-detail layout so the NavHost keeps its place in composition.
    CompositionLocalProvider(LocalSettingsListState provides settingsListState) {
        RipDpiListDetailLayout(
            showList = settingsListDetail != null,
            modifier = modifier,
            listPane = {
                SettingsListPane(
                    navController = navController,
                    selected = settingsListDetail?.selected,
                    actions = actions,
                    mainViewModel = mainViewModel,
                    permissionSummary = permissionSummary,
                )
            },
            detailPane = {
                RipDpiNavGraph(
                    startDestination = startDestination,
                    innerPadding = PaddingValues(),
                    navController = navController,
                    motion = motion,
                    actions = actions,
                    mainViewModel = mainViewModel,
                    pauseViewModel = pauseViewModel,
                    permissionSummary = permissionSummary,
                    diagnosticsInitialSection = diagnosticsInitialSection.value,
                    onDiagnosticsInitialSectionChanged = { diagnosticsInitialSection.value = it },
                )
            },
        )
    }
}

/**
 * The bar hides with the outgoing screen, not before it: while two screens are on stage in a NavHost transition the tab
 * that was selected stays selected, and the bar goes when the transition has settled. Arriving at a tab applies at
 * once.
 */
@Composable
private fun rememberHeldTopLevel(
    selected: Route?,
    navController: NavHostController,
): Route? {
    val visibleEntries by navController.visibleEntries.collectAsState()
    val transitioning = visibleEntries.count { it.destination !is NavGraph } > 1
    var lastTab by remember { mutableStateOf(selected) }
    if (selected != null) SideEffect { lastTab = selected }
    return heldTopLevel(selected, lastTab, transitioning)
}

/**
 * No tab is selected, so no bottom bar or rail shows, while onboarding is on the back stack. A Settings detail
 * shown beside its list keeps the Settings tab selected, so the rail stays beside both panes.
 */
@Composable
private fun NavHostController.selectedTopLevelRoute(entry: NavBackStackEntry?): Route? {
    val settingsListDetail = settingsListDetailFor(entry)
    return when {
        hasOnboardingEntry(entry) -> null
        settingsListDetail != null -> Route.Settings
        else -> entry?.destination?.topLevelRoute()
    }
}

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
    pauseViewModel: StatusPauseViewModel,
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
        enterTransition = { routeEnter(motion) },
        exitTransition = { routeExit(motion) },
        popEnterTransition = { routePopEnter(motion) },
        popExitTransition = { routePopExit(motion) },
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

internal fun NavHostController.navigateConfigSubRoute(destination: Route) {
    navigate(destination) {
        launchSingleTop = true
        restoreState = true
        popUpTo<Route.Home> { saveState = true }
    }
}

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

// A shared-axis screen travels a tenth of its width while it fades.
private const val SharedAxisTravelDivisor = 10

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTopLevel(): Boolean =
    Route.topLevel.any { initialState.destination.matchesRoute(it) } &&
        Route.topLevel.any { targetState.destination.matchesRoute(it) }

/** Tab switches fade through; pushing a screen slides it in along the horizontal shared axis. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.routeEnter(motion: RipDpiMotion): EnterTransition =
    when {
        betweenTopLevel() -> {
            motion.fadeThroughEnterTransition()
        }

        !motion.animationsEnabled -> {
            EnterTransition.None
        }

        else -> {
            slideIntoContainer(
                towards = SlideDirection.Start,
                animationSpec = motion.incomingSlideTween(),
                initialOffset = { it / SharedAxisTravelDivisor },
            ) + fadeIn(animationSpec = motion.incomingFadeTween())
        }
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.routeExit(motion: RipDpiMotion): ExitTransition =
    when {
        betweenTopLevel() || !motion.animationsEnabled -> {
            motion.outgoingFadeTransition()
        }

        else -> {
            slideOutOfContainer(
                towards = SlideDirection.Start,
                animationSpec = motion.quickTween(),
                targetOffset = { it / SharedAxisTravelDivisor },
            ) + motion.outgoingFadeTransition()
        }
    }

/** Returning to a tab fades through as well; any other pop keeps the seekable predictive-back transitions. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.routePopEnter(motion: RipDpiMotion): EnterTransition =
    if (betweenTopLevel()) motion.fadeThroughEnterTransition() else motion.routePopEnterTransition()

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.routePopExit(motion: RipDpiMotion): ExitTransition =
    if (betweenTopLevel()) motion.outgoingFadeTransition() else motion.routePopExitTransition()
