package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
class BottomNavBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun maximumFontUsesNonOverlappingCompactLabelsAndFullAccessibilityNames() {
        var fontScale by mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    Box(
                        modifier =
                            Modifier
                                .requiredWidth(411.dp)
                                .height(120.dp),
                    ) {
                        BottomNavBar(
                            selectedRoute = Route.Home,
                            onNavigate = {},
                        )
                    }
                }
            }
        }

        mapOf(
            1f to listOf("Status", "Connection", "Diagnose", "Settings"),
            1.5f to listOf("Status", "Connect", "Checks", "Settings"),
            2f to listOf("Status", "Connect", "Checks", "Settings"),
        ).forEach { (scale, expectedLabels) ->
            composeRule.runOnIdle { fontScale = scale }
            composeRule.waitForIdle()
            val labels =
                expectedLabels.map { label ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    composeRule
                        .onNodeWithText(label, useUnmergedTree = true)
                        .assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
                        .fetchSemanticsNode()
                        .also {
                            val layout = layouts.single()
                            assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
                            assertTrue((0 until layout.lineCount).all { line -> layout.getLineLeft(line) >= -1f })
                            assertTrue(
                                (0 until layout.lineCount).all { line ->
                                    layout.getLineRight(line) <= layout.size.width + 1f
                                },
                            )
                        }
                }
            labels.zipWithNext().forEach { (left, right) ->
                assertTrue(left.boundsInRoot.right <= right.boundsInRoot.left)
            }
            Route.topLevel.zip(listOf("Status", "Connection", "Diagnose", "Settings")).forEach { (route, label) ->
                composeRule
                    .onAllNodesWithContentDescription(label, useUnmergedTree = true)
                    .assertCountEquals(1)
                composeRule
                    .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
                    .assertHasClickAction()
                    .run {
                        if (route == Route.Home) assertIsSelected() else assertIsNotSelected()
                    }
            }
        }
    }

    @Test
    fun rtlSelectionIndicatorStaysInsideEverySelectedTab() {
        var selectedRoute by mutableStateOf<Route>(Route.Home)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    Box(
                        modifier =
                            Modifier
                                .requiredWidth(411.dp)
                                .height(120.dp),
                    ) {
                        BottomNavBar(
                            selectedRoute = selectedRoute,
                            onNavigate = {},
                        )
                    }
                }
            }
        }

        Route.topLevel.forEach { route ->
            composeRule.runOnIdle { selectedRoute = route }
            composeRule.mainClock.advanceTimeBy(1_000L)
            composeRule.waitForIdle()

            val indicator =
                composeRule
                    .onNodeWithTag(RipDpiTestTags.BottomNavIndicator)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
            val selectedTab =
                composeRule
                    .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    .assertIsDisplayed()
                    .fetchSemanticsNode()

            assertTrue(indicator.boundsInRoot.left >= selectedTab.boundsInRoot.left)
            assertTrue(indicator.boundsInRoot.right <= selectedTab.boundsInRoot.right)
        }
    }
}
