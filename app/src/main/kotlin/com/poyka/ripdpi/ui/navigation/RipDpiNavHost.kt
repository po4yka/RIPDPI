package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import com.poyka.ripdpi.activities.ConfigViewModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.activities.MainUiState
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.screens.config.ConfigRoute
import com.poyka.ripdpi.ui.screens.config.ModeEditorRoute
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationRoute
import com.poyka.ripdpi.ui.screens.detection.DetectionCheckRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
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
        relockToRoute = { destination ->
            navController.navigate(destination) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        },
    )

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            snackbarHostState?.let { hostState ->
                RipDpiSnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.padding(horizontal = layout.horizontalPadding),
                )
            }
        },
        bottomBar = {
            TopLevelBottomBar(
                selectedTopLevel = selectedTopLevel,
                onNavigate = { destination -> navController.navigateTopLevel(destination) },
            )
        },
    ) { innerPadding ->
        RipDpiNavGraph(
            startDestination = startDestination,
            innerPadding = innerPadding,
            navController = navController,
            animationsEnabled = motion.animationsEnabled,
            routeDurationMillis = motion.duration(motion.routeDurationMillis),
            quickDurationMillis = motion.duration(motion.quickDurationMillis),
            actions = actions,
            mainViewModel = mainViewModel,
            mainUiState = mainUiState,
            diagnosticsInitialSection = diagnosticsInitialSection.value,
            onDiagnosticsInitialSectionChanged = { diagnosticsInitialSection.value = it },
        )
    }
}

@Composable
private fun HandleLaunchRequests(
    launchRequests: RipDpiNavHostLaunchRequests,
    currentStableRoute: String?,
    navigateHome: () -> Unit,
    navigateToRoute: (Route) -> Unit,
    relockToRoute: (Route) -> Unit,
) {
    LaunchedEffect(launchRequests.launchHomeRequested, currentStableRoute) {
        if (!launchRequests.launchHomeRequested || currentStableRoute == null) {
            return@LaunchedEffect
        }
        when {
            currentStableRoute == Route.Home.route -> {
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
    animationsEnabled: Boolean,
    routeDurationMillis: Int,
    quickDurationMillis: Int,
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
        enterTransition = { routeEnterTransition(animationsEnabled, routeDurationMillis) },
        exitTransition = { routeExitTransition(animationsEnabled, quickDurationMillis) },
        popEnterTransition = { routePopEnterTransition(animationsEnabled, routeDurationMillis) },
        popExitTransition = { routePopExitTransition(animationsEnabled, quickDurationMillis) },
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
                onDiagnosticsInitialSectionChanged(DiagnosticsSection.Tools)
                navController.navigate(Route.Diagnostics) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onOpenHistory = { navController.navigate(Route.History) { launchSingleTop = true } },
            onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
            viewModel = mainViewModel,
        )
    }
    composable<Route.Diagnostics>(
        deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://diagnostics" }),
    ) {
        val diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
        DiagnosticsRoute(
            onShareArchive = actions.onShareDiagnosticsArchive,
            onSaveArchive = actions.onSaveDiagnosticsArchive,
            onShareSummary = actions.onShareDiagnosticsSummary,
            onSaveLogs = actions.onSaveLogs,
            onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings) },
            onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
            onOpenDetectionCheck = { navController.navigate(Route.DetectionCheck) },
            onRequestVpnPermission = mainViewModel::onOpenVpnPermissionRequested,
            onOpenHistory = { navController.navigate(Route.History) { launchSingleTop = true } },
            initialSection = diagnosticsInitialSection,
            onInitialSectionHandled = { onDiagnosticsInitialSectionChanged(null) },
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
                mainViewModel.onAuthenticated()
                navController.navigate(Route.Home) {
                    popUpTo<Route.BiometricPrompt> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

private fun NavGraphBuilder.addConfigRoutes(navController: NavHostController) {
    navigation<ConfigGraph>(
        startDestination = Route.Config,
    ) {
        composable<Route.Config> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
                viewModel = configViewModel,
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
            val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
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
        composable<Route.DnsSettings> {
            val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            DnsSettingsRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
        }
        composable<Route.AdvancedSettings> {
            val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            AdvancedSettingsRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
        }
        composable<Route.AppCustomization> {
            val settingsGraphEntry = remember(navController, it) { navController.getBackStackEntry<SettingsGraph>() }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            AppCustomizationRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
        }
        composable<Route.DataTransparency> {
            DataTransparencyRoute(onBack = { navController.popBackStack() })
        }
        composable<Route.DetectionCheck> {
            DetectionCheckRoute(onBack = { navController.popBackStack() })
        }
    }
}

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

private fun routeEnterTransition(
    animationsEnabled: Boolean,
    routeDurationMillis: Int,
): EnterTransition =
    if (!animationsEnabled) {
        EnterTransition.None
    } else {
        fadeIn(
            animationSpec = tween(durationMillis = routeDurationMillis, easing = RipDpiMotion.EmphasizedDecelerate),
        ) +
            scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(durationMillis = routeDurationMillis, easing = RipDpiMotion.EmphasizedDecelerate),
            )
    }

private fun routeExitTransition(
    animationsEnabled: Boolean,
    quickDurationMillis: Int,
): ExitTransition =
    if (!animationsEnabled) {
        ExitTransition.None
    } else {
        fadeOut(
            animationSpec = tween(durationMillis = quickDurationMillis, easing = RipDpiMotion.EmphasizedAccelerate),
        )
    }

private fun routePopEnterTransition(
    animationsEnabled: Boolean,
    routeDurationMillis: Int,
): EnterTransition =
    if (!animationsEnabled) {
        EnterTransition.None
    } else {
        fadeIn(
            animationSpec = tween(durationMillis = routeDurationMillis, easing = RipDpiMotion.EmphasizedDecelerate),
        ) +
            scaleIn(
                initialScale = 0.992f,
                animationSpec = tween(durationMillis = routeDurationMillis, easing = RipDpiMotion.EmphasizedDecelerate),
            )
    }

private fun routePopExitTransition(
    animationsEnabled: Boolean,
    quickDurationMillis: Int,
): ExitTransition =
    if (!animationsEnabled) {
        ExitTransition.None
    } else {
        fadeOut(
            animationSpec = tween(durationMillis = quickDurationMillis, easing = RipDpiMotion.EmphasizedAccelerate),
        ) +
            scaleOut(
                targetScale = 0.992f,
                animationSpec = tween(durationMillis = quickDurationMillis, easing = RipDpiMotion.EmphasizedAccelerate),
            )
    }

internal fun nestedEnterTransition(
    animationsEnabled: Boolean,
    routeDurationMillis: Int,
): EnterTransition =
    if (!animationsEnabled) {
        EnterTransition.None
    } else {
        slideInHorizontally(
            initialOffsetX = { fullWidth -> (fullWidth * 0.15f).toInt() },
            animationSpec = tween(durationMillis = routeDurationMillis, easing = RipDpiMotion.EmphasizedDecelerate),
        ) +
            fadeIn(
                animationSpec = tween(durationMillis = routeDurationMillis, easing = RipDpiMotion.EmphasizedDecelerate),
            )
    }

internal fun nestedPopExitTransition(
    animationsEnabled: Boolean,
    quickDurationMillis: Int,
): ExitTransition =
    if (!animationsEnabled) {
        ExitTransition.None
    } else {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> (fullWidth * 0.15f).toInt() },
            animationSpec = tween(durationMillis = quickDurationMillis, easing = RipDpiMotion.EmphasizedAccelerate),
        ) +
            fadeOut(
                animationSpec = tween(durationMillis = quickDurationMillis, easing = RipDpiMotion.EmphasizedAccelerate),
            )
    }

/**
 * Returns the stable route key (e.g. "home") for a Nav destination.
 *
 * Needed because Navigation Compose 2.8+ typed routes expose `destination.route` as a
 * fully-qualified class name, not the stable key that [BottomNavBar] and the
 * launch-request pipeline still speak. The stable key is preserved on [Route] itself
 * so external surfaces (automation, telemetry, deep links) keep their string contract.
 */
internal fun NavDestination.stableRouteKey(): String? =
    when {
        hasRoute<Route.Home>() -> Route.Home.route
        hasRoute<Route.Config>() -> Route.Config.route
        hasRoute<Route.Diagnostics>() -> Route.Diagnostics.route
        hasRoute<Route.Settings>() -> Route.Settings.route
        hasRoute<Route.Onboarding>() -> Route.Onboarding.route
        hasRoute<Route.History>() -> Route.History.route
        hasRoute<Route.Logs>() -> Route.Logs.route
        hasRoute<Route.ModeEditor>() -> Route.ModeEditor.route
        hasRoute<Route.DnsSettings>() -> Route.DnsSettings.route
        hasRoute<Route.AdvancedSettings>() -> Route.AdvancedSettings.route
        hasRoute<Route.BiometricPrompt>() -> Route.BiometricPrompt.route
        hasRoute<Route.AppCustomization>() -> Route.AppCustomization.route
        hasRoute<Route.About>() -> Route.About.route
        hasRoute<Route.DataTransparency>() -> Route.DataTransparency.route
        hasRoute<Route.DetectionCheck>() -> Route.DetectionCheck.route
        else -> null
    }

internal fun NavDestination.matchesRoute(route: Route): Boolean =
    when (route) {
        Route.Home -> hasRoute<Route.Home>()
        Route.Config -> hasRoute<Route.Config>()
        Route.Diagnostics -> hasRoute<Route.Diagnostics>()
        Route.Settings -> hasRoute<Route.Settings>()
        Route.Onboarding -> hasRoute<Route.Onboarding>()
        Route.History -> hasRoute<Route.History>()
        Route.Logs -> hasRoute<Route.Logs>()
        Route.ModeEditor -> hasRoute<Route.ModeEditor>()
        Route.DnsSettings -> hasRoute<Route.DnsSettings>()
        Route.AdvancedSettings -> hasRoute<Route.AdvancedSettings>()
        Route.BiometricPrompt -> hasRoute<Route.BiometricPrompt>()
        Route.AppCustomization -> hasRoute<Route.AppCustomization>()
        Route.About -> hasRoute<Route.About>()
        Route.DataTransparency -> hasRoute<Route.DataTransparency>()
        Route.DetectionCheck -> hasRoute<Route.DetectionCheck>()
    }

internal fun shouldNavigateToHomeFromLaunchRequest(
    launchHomeRequested: Boolean,
    currentRoute: String?,
): Boolean {
    if (!launchHomeRequested || currentRoute == null) {
        return false
    }

    return currentRoute != Route.Home.route &&
        currentRoute !in
        setOf(
            Route.Onboarding.route,
            Route.BiometricPrompt.route,
        )
}
