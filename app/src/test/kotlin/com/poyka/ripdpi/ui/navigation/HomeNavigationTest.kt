package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun finishedImportReturnsHome() {
        showNavigationGraph()
        composeRule.runOnIdle {
            navController.navigate(Route.ProfileImportConfirm(importToken = "token")) { launchSingleTop = true }
            navController.navigateHome()
        }
        composeRule.runOnIdle { assertOnlyHome() }
    }

    @Test
    fun settingsTabReturnsHome() {
        showNavigationGraph()
        composeRule.runOnIdle {
            navController.navigate(Route.Settings)
            navController.navigateHome()
        }
        composeRule.runOnIdle { assertOnlyHome() }
    }

    private fun assertOnlyHome() {
        assertTrue(requireNotNull(navController.currentDestination).hasRoute<Route.Home>())
        assertEquals(1, navController.currentBackStack.value.count { it.destination.route != null })
    }

    private fun showNavigationGraph() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = Route.Home,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable<Route.Home> {}
                // Import routes are registered inside the settings graph, as in RipDpiNavHost.
                navigation<SettingsGraph>(startDestination = Route.Settings) {
                    composable<Route.Settings> {}
                    composable<Route.ProfileImportConfirm> {}
                }
            }
        }
        composeRule.waitForIdle()
    }
}
