package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.activities.DiagnosticsViewModel
import com.poyka.ripdpi.activities.DiagnosticsSection
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.screens.config.ConfigRoute
import com.poyka.ripdpi.ui.screens.config.ModeEditorRoute
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.screens.history.HistoryRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingRoute
import com.poyka.ripdpi.ui.screens.permissions.BiometricPromptRoute
import com.poyka.ripdpi.ui.screens.permissions.VpnPermissionRoute
import com.poyka.ripdpi.ui.screens.settings.AdvancedSettingsRoute
import com.poyka.ripdpi.ui.screens.settings.SettingsRoute
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val SettingsGraphRoute = "settings_graph"

@Composable
fun RipDpiNavHost(
    modifier: Modifier = Modifier,
    startDestination: String = Route.Home.route,
    onSaveLogs: () -> Unit = {},
    onSaveDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    onShareDiagnosticsArchive: (String, String) -> Unit = { _, _ -> },
    onShareDiagnosticsSummary: (String, String) -> Unit = { _, _ -> },
    mainViewModel: MainViewModel,
    openVpnPermissionRequested: Boolean = false,
    onOpenVpnPermissionHandled: () -> Unit = {},
    launchHomeRequested: Boolean = false,
    onLaunchHomeHandled: () -> Unit = {},
    onStartConfiguredMode: () -> Unit = {},
    onOpenVpnPermission: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    onRepairPermission: (PermissionKind) -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val navController = rememberNavController()
    val diagnosticsInitialSection = remember { mutableStateOf<DiagnosticsSection?>(null) }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val layout = RipDpiThemeTokens.layout
    val motion = RipDpiThemeTokens.motion
    val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(launchHomeRequested, currentDestination?.route) {
        val currentRoute = currentDestination?.route
        if (!launchHomeRequested || currentRoute == null) {
            return@LaunchedEffect
        }

        when {
            currentRoute == Route.Home.route -> {
                onLaunchHomeHandled()
            }

            shouldNavigateToHomeFromLaunchRequest(
                launchHomeRequested = launchHomeRequested,
                currentRoute = currentRoute,
            ) -> {
                navController.navigate(Route.Home.route) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(Route.Home.route) {
                        saveState = true
                    }
                }
                onLaunchHomeHandled()
            }
        }
    }

    LaunchedEffect(openVpnPermissionRequested, currentDestination?.route) {
        val currentRoute = currentDestination?.route
        if (!openVpnPermissionRequested || currentRoute == null) {
            return@LaunchedEffect
        }

        if (currentRoute == Route.VpnPermission.route) {
            onOpenVpnPermissionHandled()
            return@LaunchedEffect
        }

        navController.navigate(Route.VpnPermission.route) {
            launchSingleTop = true
        }
        onOpenVpnPermissionHandled()
    }

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
            if (currentDestination.isTopLevelDestination()) {
                BottomNavBar(
                    currentRoute = currentDestination?.route,
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Route.Home.route) {
                                saveState = true
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (!motion.animationsEnabled) {
                    EnterTransition.None
                } else {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = motion.duration(motion.routeDurationMillis),
                            easing = FastOutSlowInEasing,
                        ),
                    ) +
                        scaleIn(
                            initialScale = 0.985f,
                            animationSpec = tween(
                                durationMillis = motion.duration(motion.routeDurationMillis),
                                easing = FastOutSlowInEasing,
                            ),
                        )
                }
            },
            exitTransition = {
                if (!motion.animationsEnabled) {
                    ExitTransition.None
                } else {
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = motion.duration(motion.quickDurationMillis),
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
            },
            popEnterTransition = {
                if (!motion.animationsEnabled) {
                    EnterTransition.None
                } else {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = motion.duration(motion.routeDurationMillis),
                            easing = FastOutSlowInEasing,
                        ),
                    ) +
                        scaleIn(
                            initialScale = 0.992f,
                            animationSpec = tween(
                                durationMillis = motion.duration(motion.routeDurationMillis),
                                easing = FastOutSlowInEasing,
                            ),
                        )
                }
            },
            popExitTransition = {
                if (!motion.animationsEnabled) {
                    ExitTransition.None
                } else {
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = motion.duration(motion.quickDurationMillis),
                            easing = FastOutSlowInEasing,
                        ),
                    ) +
                        scaleOut(
                            targetScale = 0.992f,
                            animationSpec = tween(
                                durationMillis = motion.duration(motion.quickDurationMillis),
                                easing = FastOutSlowInEasing,
                            ),
                        )
                }
            },
        ) {
            composable(Route.Onboarding.route) {
                OnboardingRoute(
                    onComplete = {
                        navController.navigate(Route.Home.route) {
                            popUpTo(Route.Onboarding.route) {
                                inclusive = true
                            }
                        }
                    },
                )
            }
            composable(Route.Home.route) {
                HomeRoute(
                    onStartConfiguredMode = onStartConfiguredMode,
                    onOpenDiagnostics = {
                        diagnosticsInitialSection.value = DiagnosticsSection.Approaches
                        navController.navigate(Route.Diagnostics.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenHistory = {
                        navController.navigate(Route.History.route) {
                            launchSingleTop = true
                        }
                    },
                    viewModel = mainViewModel,
                )
            }
            composable(Route.Config.route) {
                ConfigRoute(
                    onOpenModeEditor = { navController.navigate(Route.ModeEditor.route) },
                    onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                )
            }
            composable(Route.Diagnostics.route) {
                val diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
                DiagnosticsRoute(
                    onShareArchive = onShareDiagnosticsArchive,
                    onSaveArchive = onSaveDiagnosticsArchive,
                    onShareSummary = onShareDiagnosticsSummary,
                    onSaveLogs = onSaveLogs,
                    onOpenHistory = {
                        navController.navigate(Route.History.route) {
                            launchSingleTop = true
                        }
                    },
                    initialSection = diagnosticsInitialSection.value,
                    onInitialSectionHandled = { diagnosticsInitialSection.value = null },
                    viewModel = diagnosticsViewModel,
                )
            }
            composable(Route.History.route) {
                HistoryRoute(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.ModeEditor.route) {
                val configBackStackEntry =
                    remember(navController) {
                        navController.getBackStackEntry(Route.Config.route)
                    }
                ModeEditorRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel(configBackStackEntry),
                )
            }
            composable(Route.VpnPermission.route) {
                VpnPermissionRoute(
                    onDismiss = { navController.popBackStack() },
                    onGranted = { navController.popBackStack() },
                    onContinue = onRequestVpnPermission,
                    viewModel = mainViewModel,
                )
            }
            composable(Route.BiometricPrompt.route) {
                BiometricPromptRoute(
                    onAuthenticated = {
                        navController.navigate(Route.Home.route) {
                            popUpTo(Route.BiometricPrompt.route) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                )
            }
            navigation(
                startDestination = Route.Settings.route,
                route = SettingsGraphRoute,
            ) {
                composable(Route.Settings.route) {
                    val settingsGraphEntry =
                        remember(navController) {
                            navController.getBackStackEntry(SettingsGraphRoute)
                        }
                    val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
                    SettingsRoute(
                        onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                        onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings.route) },
                        onOpenCustomization = { navController.navigate(Route.AppCustomization.route) },
                        onOpenAbout = { navController.navigate(Route.About.route) },
                        permissionSummary = mainUiState.permissionSummary,
                        onRepairPermission = onRepairPermission,
                        viewModel = settingsViewModel,
                    )
                }
                composable(Route.DnsSettings.route) {
                    val settingsGraphEntry =
                        remember(navController) {
                            navController.getBackStackEntry(SettingsGraphRoute)
                        }
                    val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
                    DnsSettingsRoute(
                        onBack = { navController.popBackStack() },
                        viewModel = settingsViewModel,
                    )
                }
                composable(Route.AdvancedSettings.route) {
                    val settingsGraphEntry =
                        remember(navController) {
                            navController.getBackStackEntry(SettingsGraphRoute)
                        }
                    val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
                    AdvancedSettingsRoute(
                        onBack = { navController.popBackStack() },
                        viewModel = settingsViewModel,
                    )
                }
                composable(Route.AppCustomization.route) {
                    val settingsGraphEntry =
                        remember(navController) {
                            navController.getBackStackEntry(SettingsGraphRoute)
                        }
                    val settingsViewModel: SettingsViewModel = hiltViewModel(settingsGraphEntry)
                    AppCustomizationRoute(
                        onBack = { navController.popBackStack() },
                        viewModel = settingsViewModel,
                    )
                }
            }
            composable(Route.About.route) {
                AboutRoute(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavDestination?.isTopLevelDestination(): Boolean =
    this?.hierarchy?.any { destination ->
        Route.topLevel.any { topLevel -> topLevel.route == destination.route }
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
