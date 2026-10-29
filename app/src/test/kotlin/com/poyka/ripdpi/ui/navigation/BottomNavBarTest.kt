package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.test.onAllNodesWithText
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
import com.poyka.ripdpi.ui.theme.bottomNavigationFocusBorderColor
import org.junit.Assert.assertEquals
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
    fun everyFontScaleKeepsOneRowOfFourTabsWithAccessibilityNames() {
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

        val labels = listOf("Status", "Connection", "Diagnostics", "Settings")
        listOf(1f, 1.5f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale.value = scale }
            composeRule.waitForIdle()
            assertOneRow(LayoutDirection.Ltr)
            assertFullAccessibilityNames(labels)
            if (scale < 1.5f) {
                labels.forEach(::assertSingleLineLabelInsideItsTab)
            } else {
                labels.forEach(::assertLabelHidden)
            }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.BottomNavBar).assertIsDisplayed()
    }

    @Test
    fun narrowLargeFontNavigationKeepsEveryTabAndCallback() = assertResponsiveNavigation()

    @Test
    fun maximumFontKeepsOneRowOfCompactTargetsInBothDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        var selected by mutableStateOf<Route>(Route.Home)
        val navigated = mutableListOf<Route>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides direction.value,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(360.dp).height(900.dp)) {
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
            assertOneRow(layoutDirection).forEach { (route, bounds) ->
                assertTrue("$route has a 48dp target", bounds.height >= 48f && bounds.width >= 48f)
                composeRule
                    .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
            }
            Route.topLevel.forEach { route ->
                assertLabelHidden(RuntimeEnvironment.getApplication().getString(route.titleRes))
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
        assertEquals(8, navigated.size)
    }

    @Test
    @Config(qualifiers = "ar-rEG-w360dp-h1200dp")
    fun arabicMaximumFontNavigationKeepsOneRowWithinCompactHeight() {
        var selected by mutableStateOf<Route>(Route.Home)
        val navigated = mutableListOf<Route>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(360.dp).height(480.dp)) {
                        BottomNavBar(selected, {
                            selected = it
                            navigated += it
                        })
                    }
                }
            }
        }
        val bar = composeRule.onNodeWithTag(RipDpiTestTags.BottomNavBar).fetchSemanticsNode().boundsInRoot
        assertTrue("Arabic360dp/font2 navigation height is ${bar.height}", bar.height <= 112f)
        val labels = Route.topLevel.map { RuntimeEnvironment.getApplication().getString(it.titleRes) }
        assertOneRow(LayoutDirection.Rtl)
        assertFullAccessibilityNames(labels)
        Route.topLevel.forEach { route ->
            val target = composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(route))
            target
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
            val targetBounds = target.fetchSemanticsNode().boundsInRoot
            assertTrue("$route retains a 48dp target", targetBounds.height >= 48f && targetBounds.width >= 48f)
            assertTrue("$route stays inside the bar", bar.contains(targetBounds.center))
            target.performClick().assertIsSelected()
            Route.topLevel.filter { it != route }.forEach {
                composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(it)).assertIsNotSelected()
            }
            assertEquals(route, navigated.last())
        }
        assertEquals(Route.topLevel, navigated)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w420dp-h1200dp")
    fun russianLargeFontNavigationKeepsEveryTabAndCallback() = assertResponsiveNavigation()

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
            assertOneRow(layoutDirection)
            assertFullAccessibilityNames(labels)
            if (fontScale < 1.5f) {
                labels.forEach(::assertSingleLineLabelInsideItsTab)
            } else {
                labels.forEach(::assertLabelHidden)
            }
            Route.topLevel.forEach { route ->
                composeRule
                    .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .performClick()
                    .assertIsSelected()
                assertEquals("$screenWidth/$fontScale/$layoutDirection", route, navigated.last())
            }
        }
        assertEquals(32, navigated.size)
    }

    /** Asserts that the four tabs share one row in reading order and returns their bounds. */
    private fun assertOneRow(direction: LayoutDirection): List<Pair<Route, Rect>> {
        val tabs =
            Route.topLevel.map { route ->
                route to
                    composeRule
                        .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
            }
        tabs.zipWithNext().forEach { (first, second) ->
            // Centres, not edges: the selected tab is drawn slightly scaled up.
            assertEquals(
                "${first.first} and ${second.first} share one row",
                first.second.center.y,
                second.second.center.y,
                1f,
            )
            assertEquals(
                "${first.first} precedes ${second.first} in $direction",
                direction == LayoutDirection.Ltr,
                first.second.center.x < second.second.center.x,
            )
        }
        return tabs
    }

    private fun assertFullAccessibilityNames(labels: List<String>) {
        Route.topLevel.zip(labels).forEach { (route, label) ->
            composeRule
                .onAllNodesWithContentDescription(label, useUnmergedTree = true)
                .assertCountEquals(1)
            composeRule
                .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
                .assertHasClickAction()
        }
    }

    private fun assertSingleLineLabelInsideItsTab(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val text =
            composeRule
                .onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("$label stays on one line", 1, layouts.single().lineCount)
        val tab =
            Route.topLevel
                .map { composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(it)).fetchSemanticsNode().boundsInRoot }
                .single { it.contains(text.center) }
        assertTrue("$label stays inside its tab", text.left >= tab.left - 1f && text.right <= tab.right + 1f)
    }

    private fun assertLabelHidden(label: String) {
        composeRule.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
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
        assertEquals(outline, bottomNavigationFocusBorderColor(isFocused = true, outlineColor = outline))
        assertEquals(Color.Transparent, bottomNavigationFocusBorderColor(isFocused = false, outlineColor = outline))
    }
}
