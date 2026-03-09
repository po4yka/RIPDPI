package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarHost
import com.poyka.ripdpi.ui.screens.config.ConfigRoute
import com.poyka.ripdpi.ui.screens.config.ModeEditorRoute
import com.poyka.ripdpi.ui.screens.customization.AboutRoute
import com.poyka.ripdpi.ui.screens.customization.AppCustomizationRoute
import com.poyka.ripdpi.ui.screens.dns.DnsSettingsRoute
import com.poyka.ripdpi.ui.screens.home.HomeRoute
import com.poyka.ripdpi.ui.screens.logs.LogsRoute
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
    mainViewModel: MainViewModel,
    openVpnPermissionRequested: Boolean = false,
    onOpenVpnPermissionHandled: () -> Unit = {},
    launchHomeRequested: Boolean = false,
    onLaunchHomeHandled: () -> Unit = {},
    onStartConfiguredMode: () -> Unit = {},
    onOpenVpnPermission: () -> Unit = {},
    onRequestVpnPermission: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val layout = RipDpiThemeTokens.layout

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
                    onOpenVpnPermission = onOpenVpnPermission,
                    onStartConfiguredMode = onStartConfiguredMode,
                    viewModel = mainViewModel,
                )
            }
            composable(Route.Config.route) {
                ConfigRoute(
                    onOpenModeEditor = { navController.navigate(Route.ModeEditor.route) },
                    onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                )
            }
            composable(Route.Logs.route) {
                LogsRoute(
                    onSaveLogs = onSaveLogs,
                )
            }
            composable(Route.ModeEditor.route) {
                val configBackStackEntry =
                    remember(navController) {
                        navController.getBackStackEntry(Route.Config.route)
                    }
                ModeEditorRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel(viewModelStoreOwner = configBackStackEntry),
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
                    val settingsViewModel: SettingsViewModel = viewModel(viewModelStoreOwner = settingsGraphEntry)
                    SettingsRoute(
                        onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                        onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings.route) },
                        onOpenCustomization = { navController.navigate(Route.AppCustomization.route) },
                        onOpenAbout = { navController.navigate(Route.About.route) },
                        viewModel = settingsViewModel,
                    )
                }
                composable(Route.DnsSettings.route) {
                    val settingsGraphEntry =
                        remember(navController) {
                            navController.getBackStackEntry(SettingsGraphRoute)
                        }
                    val settingsViewModel: SettingsViewModel = viewModel(viewModelStoreOwner = settingsGraphEntry)
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
                    val settingsViewModel: SettingsViewModel = viewModel(viewModelStoreOwner = settingsGraphEntry)
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
                    val settingsViewModel: SettingsViewModel = viewModel(viewModelStoreOwner = settingsGraphEntry)
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
