package com.poyka.ripdpi.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.junit.Assert.assertFalse
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
class HistoryNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Test
    fun diagnosticsActionDoesNotRestoreAnOldAutomaticScanRequest() {
        showNavigationGraph()
        composeRule.runOnIdle {
            navController.navigate(Route.Diagnostics(autoStartScan = true))
            saveTopLevelStackAndOpenHistory()
            navController.navigateToDiagnosticsFromHistory()
        }
        composeRule.runOnIdle {
            val entry = requireNotNull(navController.currentBackStackEntry)
            assertTrue(entry.destination.hasRoute<Route.Diagnostics>())
            assertFalse(entry.toRoute<Route.Diagnostics>().autoStartScan)
            assertBackReturnsToHistory()
        }
    }

    @Test
    fun diagnosticsActionDoesNotRestoreANestedLogsScreen() {
        showNavigationGraph()
        composeRule.runOnIdle {
            navController.navigate(Route.Diagnostics())
            navController.navigate(Route.Logs)
            saveTopLevelStackAndOpenHistory()
            navController.navigateToDiagnosticsFromHistory()
        }
        composeRule.runOnIdle {
            val entry = requireNotNull(navController.currentBackStackEntry)
            assertTrue(entry.destination.hasRoute<Route.Diagnostics>())
            assertFalse(entry.toRoute<Route.Diagnostics>().autoStartScan)
            assertBackReturnsToHistory()
        }
    }

    @Test
    fun connectionActionDoesNotRestoreANestedProfileEditor() {
        showNavigationGraph()
        composeRule.runOnIdle {
            navController.navigate(Route.Config)
            navController.navigate(Route.ModeEditor)
            saveTopLevelStackAndOpenHistory()
            navController.navigateToConnectionFromHistory()
        }
        composeRule.runOnIdle {
            assertTrue(requireNotNull(navController.currentDestination).hasRoute<Route.Config>())
            assertBackReturnsToHistory()
        }
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
                composable<Route.History> {}
                composable<Route.Config> {}
                composable<Route.ModeEditor> {}
                composable<Route.Diagnostics> {}
                composable<Route.Logs> {}
            }
        }
        composeRule.waitForIdle()
    }

    private fun saveTopLevelStackAndOpenHistory() {
        navController.navigate(Route.Home) {
            popUpTo<Route.Home> { saveState = true }
            launchSingleTop = true
        }
        navController.navigate(Route.History)
        assertTrue(requireNotNull(navController.currentDestination).hasRoute<Route.History>())
    }

    private fun assertBackReturnsToHistory() {
        assertTrue(navController.popBackStack())
        assertTrue(requireNotNull(navController.currentDestination).hasRoute<Route.History>())
    }
}
