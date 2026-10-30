package com.poyka.ripdpi.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.poyka.ripdpi.ui.theme.RipDpiMotion
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
class PredictiveBackNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private lateinit var backDispatcher: OnBackPressedDispatcher

    @Test
    fun backGesturePreviewsThePreviousDestinationThenPopsOnCommit() {
        showGraph(RipDpiMotion())
        openAbout()

        composeRule.runOnIdle {
            backDispatcher.dispatchOnBackStarted(backEvent(progress = 0f))
            backDispatcher.dispatchOnBackProgressed(backEvent(progress = 0.5f))
        }
        composeRule.onNodeWithTag(HOME_TAG).assertExists()
        composeRule.onNodeWithTag(ABOUT_TAG).assertExists()
        composeRule.runOnIdle { assertTrue(currentIs<Route.About>()) }

        composeRule.runOnIdle { backDispatcher.onBackPressed() }

        composeRule.runOnIdle { assertTrue(currentIs<Route.Home>()) }
        composeRule.onNodeWithTag(ABOUT_TAG).assertDoesNotExist()
    }

    @Test
    fun cancelledBackGestureKeepsTheDestination() {
        showGraph(RipDpiMotion())
        openAbout()

        composeRule.runOnIdle {
            backDispatcher.dispatchOnBackStarted(backEvent(progress = 0f))
            backDispatcher.dispatchOnBackProgressed(backEvent(progress = 0.7f))
            backDispatcher.dispatchOnBackCancelled()
        }

        composeRule.runOnIdle { assertTrue(currentIs<Route.About>()) }
        composeRule.onNodeWithTag(ABOUT_TAG).assertExists()
    }

    @Test
    fun reducedMotionBackPressStillPops() {
        showGraph(RipDpiMotion(reducedMotion = true))
        openAbout()

        composeRule.runOnIdle { backDispatcher.onBackPressed() }

        composeRule.runOnIdle { assertTrue(currentIs<Route.Home>()) }
    }

    @Test
    fun unsavedChangesGuardInterceptsBackGestureAndPress() {
        var guardCalls = 0
        showGraph(RipDpiMotion(), guardAbout = { guardCalls++ })
        openAbout()

        composeRule.runOnIdle {
            backDispatcher.dispatchOnBackStarted(backEvent(progress = 0f))
            backDispatcher.dispatchOnBackProgressed(backEvent(progress = 0.5f))
            backDispatcher.onBackPressed()
        }
        composeRule.runOnIdle { backDispatcher.onBackPressed() }

        composeRule.runOnIdle {
            assertEquals(2, guardCalls)
            assertTrue(currentIs<Route.About>())
        }
        composeRule.onNodeWithTag(HOME_TAG).assertDoesNotExist()
    }

    private fun backEvent(progress: Float) =
        BackEventCompat(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    private inline fun <reified T : Any> currentIs(): Boolean =
        requireNotNull(navController.currentDestination).hasRoute<T>()

    private fun openAbout() {
        composeRule.runOnIdle { navController.navigate(Route.About) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_TAG).assertDoesNotExist()
    }

    private fun showGraph(
        motion: RipDpiMotion,
        guardAbout: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            navController = rememberNavController()
            // Same transition builders as RipDpiNavGraph.
            NavHost(
                navController = navController,
                startDestination = Route.Home,
                enterTransition = { motion.routeEnterTransition() },
                exitTransition = { motion.routeExitTransition() },
                popEnterTransition = { motion.routePopEnterTransition() },
                popExitTransition = { motion.routePopExitTransition() },
            ) {
                composable<Route.Home> { Box(Modifier.fillMaxSize().testTag(HOME_TAG)) }
                composable<Route.About> {
                    if (guardAbout != null) BackHandler(onBack = guardAbout)
                    Box(Modifier.fillMaxSize().testTag(ABOUT_TAG))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val HOME_TAG = "predictive-back-home"
        const val ABOUT_TAG = "predictive-back-about"
    }
}
