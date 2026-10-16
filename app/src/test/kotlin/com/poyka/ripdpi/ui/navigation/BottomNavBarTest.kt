package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w420dp-h1200dp")
class BottomNavBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun maximumFontKeepsNonOverlappingFullLabelsAndAccessibilityNames() {
        val fontScale = mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale.value)) {
                RipDpiTheme {
                    Box(
                        modifier =
                            Modifier
                                .requiredWidth(411.dp)
                                .height(480.dp),
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
            1.5f to listOf("Status", "Connection", "Diagnose", "Settings"),
            2f to listOf("Status", "Connection", "Diagnose", "Settings"),
        ).forEach { (scale, expectedLabels) ->
            assertBottomNavAtScale(fontScale, scale, expectedLabels)
        }
        composeRule.onNodeWithTag(RipDpiTestTags.BottomNavBar).assertIsDisplayed()
    }

    private fun assertBottomNavAtScale(
        fontScale: androidx.compose.runtime.MutableState<Float>,
        scale: Float,
        expectedLabels: List<String>,
    ) {
        composeRule.runOnIdle { fontScale.value = scale }
        composeRule.waitForIdle()
        assertLabelsDoNotOverlap(expectedLabels.map(::assertVisibleUnclippedLabel))
        assertFullAccessibilityNames()
    }

    private fun assertLabelsDoNotOverlap(labels: List<androidx.compose.ui.semantics.SemanticsNode>) {
        labels.zipWithNext().forEach { (left, right) ->
            assertTrue(
                left.boundsInRoot.right <= right.boundsInRoot.left ||
                    right.boundsInRoot.right <= left.boundsInRoot.left ||
                    left.boundsInRoot.bottom <= right.boundsInRoot.top,
            )
        }
    }

    private fun assertFullAccessibilityNames() {
        Route.topLevel.zip(listOf("Status", "Connection", "Diagnose", "Settings")).forEach { (route, label) ->
            composeRule
                .onAllNodesWithContentDescription(label, useUnmergedTree = true)
                .assertCountEquals(1)
            val node =
                composeRule
                    .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
                    .assertHasClickAction()
            if (route == Route.Home) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    private fun assertVisibleUnclippedLabel(label: String) =
        mutableListOf<TextLayoutResult>().let { layouts ->
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
                        "$label layout=${layout.size} constraints=${layout.layoutInput.constraints} " +
                            "lines=" +
                            (0 until layout.lineCount).map { "${layout.getLineLeft(it)}..${layout.getLineRight(it)}" },
                        (0 until layout.lineCount).all { line ->
                            layout.getLineRight(line) <= layout.size.width + 1f
                        },
                    )
                }
        }

    @Test
    fun narrowLargeFontNavigationKeepsEveryLabelAndCallback() = assertResponsiveNavigation()

    @Test
    fun maximumFontFallbackKeepsCompactTargetsAndFullLabelsInBothDirections() {
        val width = mutableStateOf(360)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var selected by mutableStateOf<Route>(Route.Home)
        val navigated = mutableListOf<Route>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides direction.value,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(width.value.dp).height(900.dp)) {
                        BottomNavBar(selected, {
                            selected = it
                            navigated += it
                        })
                    }
                }
            }
        }
        LayoutDirection.entries.forEach { layoutDirection ->
            composeRule.runOnIdle { direction.value = layoutDirection }
            composeRule.waitForIdle()
            val bar = composeRule.onNodeWithTag(RipDpiTestTags.BottomNavBar).fetchSemanticsNode().boundsInRoot
            assertTrue("360dp/font2 navigation height is ${bar.height}", bar.height <= 112f)
            val targets =
                Route.topLevel.map { route ->
                    val target = composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    target
                        .assertIsDisplayed()
                        .assertHasClickAction()
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                        .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
                    val bounds = target.fetchSemanticsNode().boundsInRoot
                    assertTrue("$route has a 48dp target", bounds.height >= 48f && bounds.width >= 48f)
                    bounds
                }
            assertEquals(targets[0].top, targets[1].top, 1f)
            assertTrue(targets[2].top >= targets[0].bottom - 1f)
            assertEquals(targets[2].top, targets[3].top, 1f)
            assertEquals(layoutDirection == LayoutDirection.Ltr, targets[0].left < targets[1].left)
            Route.topLevel.forEach { route ->
                assertVisibleUnclippedLabel(RuntimeEnvironment.getApplication().getString(route.titleRes))
                composeRule
                    .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    .performClick()
                    .assertIsSelected()
                Route.topLevel.filter { it != route }.forEach {
                    composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(it)).assertIsNotSelected()
                }
                assertEquals(route, navigated.last())
            }
        }
        composeRule.runOnIdle { width.value = 96 }
        composeRule.waitForIdle()
        val narrowLayouts =
            Route.topLevel.map { route ->
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule
                    .onNodeWithText(
                        RuntimeEnvironment.getApplication().getString(route.titleRes),
                        useUnmergedTree = true,
                    ).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.single().also { assertFalse("Extreme narrow label remains complete", it.hasVisualOverflow) }
            }
        assertTrue("Extreme narrow labels can wrap", narrowLayouts.any { it.lineCount > 1 })
        assertEquals(8, navigated.size)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w420dp-h1200dp")
    fun russianLargeFontNavigationKeepsEveryLabelAndCallback() = assertResponsiveNavigation()

    private fun assertResponsiveNavigation() {
        val labels = Route.topLevel.map { RuntimeEnvironment.getApplication().getString(it.titleRes) }
        val width = mutableStateOf(420)
        val scale = mutableStateOf(1f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var selectedRoute by mutableStateOf<Route>(Route.Home)
        val navigated = mutableListOf<Route>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale.value),
                LocalLayoutDirection provides direction.value,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(width.value.dp).height(480.dp)) {
                        BottomNavBar(selectedRoute, {
                            selectedRoute = it
                            navigated += it
                        })
                    }
                }
            }
        }
        val configurations =
            listOf(320, 420).flatMap { screenWidth ->
                listOf(1f, 2f).flatMap { fontScale ->
                    LayoutDirection.entries.map { Triple(screenWidth, fontScale, it) }
                }
            }
        configurations.forEach { (screenWidth, fontScale, layoutDirection) ->
            composeRule.runOnIdle {
                width.value = screenWidth
                scale.value = fontScale
                direction.value = layoutDirection
            }
            composeRule.waitForIdle()
            labels.forEach { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                composeRule
                    .onNodeWithText(label, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(
                    "$screenWidth/$fontScale/$layoutDirection/$label layout=${layouts.single().size} " +
                        "constraints=${layouts.single().layoutInput.constraints} " +
                        "lines=${layouts.single().lineCount} height=${layouts.single().multiParagraph.height}",
                    layouts.single().hasVisualOverflow,
                )
                assertEquals(1, layouts.single().lineCount)
            }
            Route.topLevel.forEach { route ->
                val target = composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(route))
                target
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .performClick()
                    .assertIsSelected()
                assertEquals(route, navigated.last())
            }
        }
        assertEquals(32, navigated.size)
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
                                .height(480.dp),
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
                    .onNodeWithTag(RipDpiTestTags.BottomNavIndicator, useUnmergedTree = true)
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

    @Test
    fun everyTabExposesKeyboardFocusActionAndVisibleFocusStyle() {
        composeRule.setContent {
            RipDpiTheme {
                BottomNavBar(
                    selectedRoute = Route.Home,
                    onNavigate = {},
                )
            }
        }

        Route.topLevel.forEach { route ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
        }
        val outline = Color(0xFF123456)
        assertEquals(outline, bottomNavFocusBorderColor(isFocused = true, outlineColor = outline))
        assertEquals(Color.Transparent, bottomNavFocusBorderColor(isFocused = false, outlineColor = outline))
    }
}
