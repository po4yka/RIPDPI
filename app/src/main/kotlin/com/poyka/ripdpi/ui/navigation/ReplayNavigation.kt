package com.poyka.ripdpi.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.poyka.ripdpi.ui.screens.diagnostics.ReplayFailureRoute

internal fun NavGraphBuilder.addReplayRoutes(navController: NavHostController) {
    composable<Route.ReplayFailure> { backStackEntry ->
        val route = backStackEntry.toRoute<Route.ReplayFailure>()
        ReplayFailureRoute(
            domain = route.domain,
            strategyId = route.strategyId,
            proxyConfigJson = route.proxyConfigJson,
            onBack = { navController.navigateUp() },
        )
    }
}
