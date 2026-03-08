package com.poyka.ripdpi.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.data.settingsStore
import com.poyka.ripdpi.ui.screens.ConfigPlaceholderScreen
import com.poyka.ripdpi.ui.screens.HomePlaceholderScreen
import com.poyka.ripdpi.ui.screens.LogsPlaceholderScreen
import com.poyka.ripdpi.ui.screens.NestedPlaceholderScreen
import com.poyka.ripdpi.ui.screens.OnboardingPlaceholderScreen
import com.poyka.ripdpi.ui.screens.SettingsPlaceholderScreen
import com.poyka.ripdpi.ui.screens.SplashPlaceholderScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

@Composable
fun RipDpiNavHost(
    modifier: Modifier = Modifier,
    onSaveLogs: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val onboardingComplete by remember(context) {
        context.settingsStore.data.map { settings -> settings.onboardingComplete }
    }.collectAsStateWithLifecycle(initialValue = false)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
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
                SplashPlaceholderScreen(
                    onboardingComplete = onboardingComplete,
                    onFinished = {
                        val destination = if (onboardingComplete) Route.Home else Route.Onboarding
                        navController.navigate(destination.route) {
                            popUpTo(Route.Splash.route) {
                                inclusive = true
                            }
                        }
                    },
                )
            }
            composable(Route.Onboarding.route) {
                OnboardingPlaceholderScreen(
                    onComplete = {
                        scope.launch {
                            context.settingsStore.updateData { settings ->
                                settings.toBuilder()
                                    .setOnboardingComplete(true)
                                    .build()
                            }
                            navController.navigate(Route.Home.route) {
                                popUpTo(Route.Onboarding.route) {
                                    inclusive = true
                                }
                            }
                        }
                    },
                )
            }
            composable(Route.Home.route) {
                HomePlaceholderScreen()
            }
            composable(Route.Config.route) {
                ConfigPlaceholderScreen(
                    onOpenModeEditor = { navController.navigate(Route.ModeEditor.route) },
                    onOpenDnsSettings = { navController.navigate(Route.DnsSettings.route) },
                )
            }
            composable(Route.Settings.route) {
                SettingsPlaceholderScreen(
                    onOpenCustomization = { navController.navigate(Route.AppCustomization.route) },
                    onOpenAbout = { navController.navigate(Route.About.route) },
                )
            }
            composable(Route.Logs.route) {
                LogsPlaceholderScreen(onSaveLogs = onSaveLogs)
            }
            composable(Route.ModeEditor.route) {
                NestedPlaceholderScreen(
                    titleRes = Route.ModeEditor.titleRes,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.DnsSettings.route) {
                NestedPlaceholderScreen(
                    titleRes = Route.DnsSettings.titleRes,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.AppCustomization.route) {
                NestedPlaceholderScreen(
                    titleRes = Route.AppCustomization.titleRes,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Route.About.route) {
                NestedPlaceholderScreen(
                    titleRes = Route.About.titleRes,
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
