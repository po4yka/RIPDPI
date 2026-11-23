package com.poyka.ripdpi.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.screens.settings.RootModeStrategiesRoute
import com.poyka.ripdpi.ui.screens.settings.RootModeTestTags
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("com.poyka.ripdpi.ui.navigation.Route.RootModeStrategies")
internal data object RootModeStrategies : Route() {
    override val stableRoute = "root_mode_strategies"
    override val titleRes = R.string.title_root_mode_strategies
    override val icon: ImageVector? = null
}

internal val distributionSettingsDetails =
    listOf(
        DistributionSettingsDetail(
            route = RootModeStrategies,
            subtitleRes = R.string.settings_root_mode_strategies_body,
            testTag = RootModeTestTags.SettingsEntry,
        ),
    )

internal fun NavGraphBuilder.addDistributionSettingsRoutes(navController: NavHostController) {
    composable<RootModeStrategies> {
        RootModeStrategiesRoute(
            onBack = { navController.popBackStack() },
            onOpenStrategyConfig = { navController.navigate(Route.StrategyConfig) },
        )
    }
}
