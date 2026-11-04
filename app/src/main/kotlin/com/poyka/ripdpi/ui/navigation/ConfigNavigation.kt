package com.poyka.ripdpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import com.poyka.ripdpi.activities.MainViewModel
import com.poyka.ripdpi.ui.screens.connection.ConnectionPath
import com.poyka.ripdpi.ui.screens.connection.ConnectionRoute
import com.poyka.ripdpi.ui.screens.connection.ConnectionViewModel
import com.poyka.ripdpi.ui.screens.connection.ModeEditorRoute

@Composable
private fun ConnectionDestination(
    entry: NavBackStackEntry,
    navController: NavHostController,
    mainViewModel: MainViewModel,
    route: Route,
    initialPath: ConnectionPath,
) {
    val configGraphEntry = remember(navController, entry) { navController.getBackStackEntry<ConfigGraph>() }
    val configViewModel: ConnectionViewModel = hiltViewModel(configGraphEntry)
    ConnectionRoute(
        runtimeIntegration = configRuntimeIntegration(mainViewModel),
        onOpenModeEditor = { navController.navigate(Route.ModeEditor) },
        onOpenLocalBypassEditor = { navController.navigate(Route.StrategyConfig) },
        onOpenDnsSettings = { navController.navigate(Route.DnsSettings) },
        onRetestStrategies = { navController.navigate(Route.Diagnostics(autoStartScan = true)) },
        onScanServer = { navController.navigate(Route.QrScanner) },
        onOpenRoute = { destination -> navController.navigate(destination) },
        route = route,
        initialPath = initialPath,
        viewModel = configViewModel,
        onImport = navController::navigateImport,
        onProfileShare = { profileId -> navController.navigate(Route.ProfileShare(profileId = profileId)) },
    )
}

internal fun NavGraphBuilder.addConfigRoutes(
    navController: NavHostController,
    mainViewModel: MainViewModel,
) {
    navigation<ConfigGraph>(
        startDestination = Route.Config,
    ) {
        composable<Route.Config>(
            deepLinks = listOf(navDeepLink { uriPattern = "$DeepLinkScheme://config" }),
        ) { ConnectionDestination(it, navController, mainViewModel, Route.Config, ConnectionPath.Direct) }
        composable<Route.LocalBypassConfig> {
            ConnectionDestination(it, navController, mainViewModel, Route.LocalBypassConfig, ConnectionPath.Direct)
        }
        composable<Route.VpnConfig> {
            ConnectionDestination(it, navController, mainViewModel, Route.VpnConfig, ConnectionPath.Relay)
        }
        composable<Route.ModeEditor> {
            val configGraphEntry = remember(navController, it) { navController.getBackStackEntry<ConfigGraph>() }
            val configViewModel: ConnectionViewModel = hiltViewModel(configGraphEntry)
            ModeEditorRoute(
                onBack = { navController.popBackStack() },
                onOpenXrayImport = { navController.navigate(Route.XrayImport) { launchSingleTop = true } },
                viewModel = configViewModel,
            )
        }
    }
}
