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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.activities.LogsViewModel
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.activities.SettingsViewModel
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.settingsStore
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
import com.poyka.ripdpi.ui.screens.splash.SplashScreen
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
fun RipDpiNavHost(
    modifier: Modifier = Modifier,
    onSaveLogs: () -> Unit = {},
    mainViewModel: MainViewModel,
    launchHomeRequested: Boolean = false,
    onLaunchHomeHandled: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current.applicationContext
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = viewModel()
    val logsViewModel: LogsViewModel = viewModel()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val layout = RipDpiThemeTokens.layout
    val settings by remember(context) {
        context.settingsStore.data
    }.collectAsStateWithLifecycle(initialValue = AppSettingsSerializer.defaultValue)

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
            startDestination = Route.Splash.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Route.Splash.route) {
                SplashScreen(
                    onFinished = {
                        val destination =
                            when {
                                !settings.onboardingComplete -> Route.Onboarding
                                settings.biometricEnabled -> Route.BiometricPrompt
                                else -> Route.Home
                            }
                        navController.navigate(destination.route) {
                            popUpTo(Route.Splash.route) {
                                inclusive = true
                            }
                        }
                    },
                )
            }
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
                    onOpenVpnPermission = {
                        navController.navigate(Route.VpnPermission.route) {
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
            composable(Route.Settings.route) {
                SettingsRoute(
                    onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                    onOpenAdvancedSettings = { navController.navigate(Route.AdvancedSettings.route) },
                    onOpenCustomization = { navController.navigate(Route.AppCustomization.route) },
                    onOpenAbout = { navController.navigate(Route.About.route) },
                    viewModel = settingsViewModel,
                )
            }
            composable(Route.Logs.route) {
                LogsRoute(
                    onSaveLogs = onSaveLogs,
                    viewModel = logsViewModel,
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
            composable(Route.DnsSettings.route) {
                DnsSettingsRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = settingsViewModel,
                )
            }
            composable(Route.AdvancedSettings.route) {
                AdvancedSettingsRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = settingsViewModel,
                )
            }
            composable(Route.VpnPermission.route) {
                VpnPermissionRoute(
                    onDismiss = { navController.popBackStack() },
                    onGranted = { navController.popBackStack() },
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
                    viewModel = settingsViewModel,
                )
            }
            composable(Route.AppCustomization.route) {
                AppCustomizationRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = settingsViewModel,
                )
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
            Route.Splash.route,
            Route.Onboarding.route,
            Route.BiometricPrompt.route,
        )
}
