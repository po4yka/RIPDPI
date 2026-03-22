package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
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
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.DataTransparencyRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val ConfigGraphRoute = "config_graph"
private const val SettingsGraphRoute = "settings_graph"

data class RipDpiNavHostActions(
    val onSaveLogs: () -> Unit = {},
    val onShareDebugBundle: () -> Unit = {},
    val onSaveDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    val onShareDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    val onShareDiagnosticsSummary: (String, String) -> Unit = { _, _ -> },
    val onStartConfiguredMode: () -> Unit = {},
    val onRepairPermission: (PermissionKind) -> Unit = {},
)

data class RipDpiNavHostLaunchRequests(
    val launchHomeRequested: Boolean = false,
    val onLaunchHomeHandled: () -> Unit = {},
    val launchRouteRequested: String? = null,
    val onLaunchRouteHandled: () -> Unit = {},
)

@Composable
fun RipDpiNavHost(
    modifier: Modifier = Modifier,
    startDestination: String = Route.Home.route,
    mainViewModel: MainViewModel,
    actions: RipDpiNavHostActions = RipDpiNavHostActions(),
    launchRequests: RipDpiNavHostLaunchRequests = RipDpiNavHostLaunchRequests(),
    snackbarHostState: SnackbarHostState? = null,
) {
    val navController = rememberNavController()
    val diagnosticsInitialSection = remember { mutableStateOf<DiagnosticsSection?>(null) }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()

    HandleLaunchRequests(
        launchRequests = launchRequests,
        currentRoute = currentDestination?.route,
        navigateHome = { navController.navigateHome() },
        navigateToRoute = { route ->
            if (route.isTopLevelRoute()) {
                navController.navigateTopLevel(route)
            } else {
                navController.navigate(route) {
                    launchSingleTop = true
                    restoreState = true
                }
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
                currentDestination = currentDestination,
                onNavigate = { destination -> navController.navigateTopLevel(destination.route) },
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
    currentRoute: String?,
    navigateHome: () -> Unit,
    navigateToRoute: (String) -> Unit,
) {
    LaunchedEffect(launchRequests.launchHomeRequested, currentRoute) {
        if (!launchRequests.launchHomeRequested || currentRoute == null) {
            return@LaunchedEffect
        }
        when {
            currentRoute == Route.Home.route -> launchRequests.onLaunchHomeHandled()
            shouldNavigateToHomeFromLaunchRequest(launchRequests.launchHomeRequested, currentRoute) -> {
                navigateHome()
                launchRequests.onLaunchHomeHandled()
            }
        }
    }

    LaunchedEffect(launchRequests.launchRouteRequested, currentRoute) {
        val requestedRoute = launchRequests.launchRouteRequested ?: return@LaunchedEffect
        val resolvedCurrentRoute = currentRoute ?: return@LaunchedEffect
        if (requestedRoute == resolvedCurrentRoute) {
            launchRequests.onLaunchRouteHandled()
            return@LaunchedEffect
        }
        navigateToRoute(requestedRoute)
        launchRequests.onLaunchRouteHandled()
    }
}

@Composable
private fun TopLevelBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (Route) -> Unit,
) {
    val topLevelDestination = currentDestination?.takeIf { it.isTopLevelDestination() } ?: return
    BottomNavBar(
        currentRoute = topLevelDestination.route,
        onNavigate = onNavigate,
    )
}

@Composable
private fun RipDpiNavGraph(
    startDestination: String,
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
        composable(Route.About.route) {
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
    composable(Route.Onboarding.route) {
        OnboardingRoute(
            onComplete = {
                navController.navigate(Route.Home.route) {
                    popUpTo(Route.Onboarding.route) { inclusive = true }
                }
            },
        )
    }
    composable(Route.Home.route) {
        HomeRoute(
            onStartConfiguredMode = actions.onStartConfiguredMode,
            onOpenDiagnostics = {
                onDiagnosticsInitialSectionChanged(DiagnosticsSection.Approaches)
                navController.navigate(Route.Diagnostics.route) {
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onOpenHistory = { navController.navigate(Route.History.route) { launchSingleTop = true } },
            onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
            viewModel = mainViewModel,
        )
    }
    composable(Route.Diagnostics.route) {
        val diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
        DiagnosticsRoute(
            onShareArchive = actions.onShareDiagnosticsArchive,
            onSaveArchive = actions.onSaveDiagnosticsArchive,
            onShareSummary = actions.onShareDiagnosticsSummary,
            onSaveLogs = actions.onSaveLogs,
            onOpenHistory = { navController.navigate(Route.History.route) { launchSingleTop = true } },
            initialSection = diagnosticsInitialSection,
            onInitialSectionHandled = { onDiagnosticsInitialSectionChanged(null) },
            viewModel = diagnosticsViewModel,
        )
    }
    composable(Route.History.route) {
        HistoryRoute(onBack = { navController.popBackStack() })
    }
    composable(Route.BiometricPrompt.route) {
        BiometricPromptRoute(
            onAuthenticated = {
                navController.navigate(Route.Home.route) {
                    popUpTo(Route.BiometricPrompt.route) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

private fun NavGraphBuilder.addConfigRoutes(
    navController: NavHostController,
) {
    navigation(
        startDestination = Route.Config.route,
        route = ConfigGraphRoute,
    ) {
        composable(Route.Config.route) {
            val configGraphEntry = remember(it) { navController.getBackStackEntry(ConfigGraphRoute) }
            val configViewModel: ConfigViewModel = hiltViewModel(configGraphEntry)
            ConfigRoute(
                onOpenModeEditor = { navController.navigate(Route.ModeEditor.route) },
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                viewModel = configViewModel,
            )
        }
        composable(Route.ModeEditor.route) {
            val configGraphEntry = remember(it) { navController.getBackStackEntry(ConfigGraphRoute) }
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
    navigation(
        startDestination = Route.Settings.route,
        route = SettingsGraphRoute,
    ) {
        composable(Route.Settings.route) {
            val settingsGraphEntry = remember(it) { navController.getBackStackEntry(SettingsGraphRoute) }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            SettingsRoute(
                onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings.route) },
                onOpenCustomization = { navController.navigate(Route.AppCustomization.route) },
                onOpenAbout = { navController.navigate(Route.About.route) },
                onOpenDataTransparency = { navController.navigate(Route.DataTransparency.route) },
                onShareDebugBundle = actions.onShareDebugBundle,
                permissionSummary = mainUiState.permissionSummary,
                onRepairPermission = actions.onRepairPermission,
                onOpenVpnPermissionDialog = mainViewModel::onOpenVpnPermissionRequested,
                viewModel = settingsViewModel,
            )
        }
        composable(Route.DnsSettings.route) {
            val settingsGraphEntry = remember(it) { navController.getBackStackEntry(SettingsGraphRoute) }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            DnsSettingsRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
        }
        composable(Route.AdvancedSettings.route) {
            val settingsGraphEntry = remember(it) { navController.getBackStackEntry(SettingsGraphRoute) }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            AdvancedSettingsRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
        }
        composable(Route.AppCustomization.route) {
            val settingsGraphEntry = remember(it) { navController.getBackStackEntry(SettingsGraphRoute) }
            val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
            AppCustomizationRoute(onBack = { navController.popBackStack() }, viewModel = settingsViewModel)
        }
        composable(Route.DataTransparency.route) {
            DataTransparencyRoute(onBack = { navController.popBackStack() })
        }
    }
}

private fun androidx.navigation.NavHostController.navigateHome() {
    navigateTopLevel(Route.Home.route)
}

private fun androidx.navigation.NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Route.Home.route) { saveState = true }
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
            animationSpec = tween(durationMillis = routeDurationMillis, easing = FastOutSlowInEasing),
        ) + scaleIn(
            initialScale = 0.985f,
            animationSpec = tween(durationMillis = routeDurationMillis, easing = FastOutSlowInEasing),
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
            animationSpec = tween(durationMillis = quickDurationMillis, easing = FastOutSlowInEasing),
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
            animationSpec = tween(durationMillis = routeDurationMillis, easing = FastOutSlowInEasing),
        ) + scaleIn(
            initialScale = 0.992f,
            animationSpec = tween(durationMillis = routeDurationMillis, easing = FastOutSlowInEasing),
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
            animationSpec = tween(durationMillis = quickDurationMillis, easing = FastOutSlowInEasing),
        ) + scaleOut(
            targetScale = 0.992f,
            animationSpec = tween(durationMillis = quickDurationMillis, easing = FastOutSlowInEasing),
        )
    }

private fun NavDestination?.isTopLevelDestination(): Boolean =
    this?.hierarchy?.any { destination ->
        destination.route.isTopLevelRoute()
    } == true

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
