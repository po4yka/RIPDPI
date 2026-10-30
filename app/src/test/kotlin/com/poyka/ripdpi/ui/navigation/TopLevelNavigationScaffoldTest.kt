package com.poyka.ripdpi.ui.navigation

import android.content.Context
import android.content.res.Configuration
import android.widget.FrameLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.ui.components.navigation.ExpandedRailFontScale
import com.poyka.ripdpi.ui.components.navigation.RipDpiNavigationItem
import com.poyka.ripdpi.ui.components.navigation.RipDpiNavigationSuiteScaffold
import com.poyka.ripdpi.ui.components.navigation.ripDpiNavigationSuiteType
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiWidthClass
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TopLevelNavigationScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each width class picks its navigation form`() {
        assertEquals(
            NavigationSuiteType.ShortNavigationBarCompact,
            ripDpiNavigationSuiteType(RipDpiWidthClass.Compact, fontScale = 1f),
        )
        assertEquals(
            NavigationSuiteType.WideNavigationRailCollapsed,
            ripDpiNavigationSuiteType(RipDpiWidthClass.Medium, fontScale = 1f),
        )
        assertEquals(
            NavigationSuiteType.WideNavigationRailCollapsed,
            ripDpiNavigationSuiteType(RipDpiWidthClass.Expanded, fontScale = 1f),
        )
        assertEquals(
            NavigationSuiteType.WideNavigationRailExpanded,
            ripDpiNavigationSuiteType(RipDpiWidthClass.Large, fontScale = 1f),
        )
        assertEquals(
            NavigationSuiteType.WideNavigationRailExpanded,
            ripDpiNavigationSuiteType(RipDpiWidthClass.ExtraLarge, fontScale = 1f),
        )
    }

    @Test
    fun `large font switches medium and expanded windows to the expanded rail`() {
        listOf(RipDpiWidthClass.Medium, RipDpiWidthClass.Expanded).forEach { widthClass ->
            assertEquals(
                NavigationSuiteType.WideNavigationRailCollapsed,
                ripDpiNavigationSuiteType(widthClass, fontScale = 1.15f),
            )
            listOf(ExpandedRailFontScale, 1.3f, 2f).forEach { scale ->
                assertEquals(
                    NavigationSuiteType.WideNavigationRailExpanded,
                    ripDpiNavigationSuiteType(widthClass, fontScale = scale),
                )
            }
        }
        assertEquals(
            NavigationSuiteType.ShortNavigationBarCompact,
            ripDpiNavigationSuiteType(RipDpiWidthClass.Compact, fontScale = 2f),
        )
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun `rail labels stay on one line on both sides of the font threshold`() {
        val labelsByLocale = listOf("pt-BR", "ru", "de", "en").map(::localizedLabels)
        val scale = mutableStateOf(1f)
        val labels = mutableStateOf(labelsByLocale.first())
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = scale.value)) {
                RipDpiTheme {
                    RipDpiNavigationSuiteScaffold(
                        items =
                            Route.topLevel
                                .mapIndexed { index, route ->
                                    RipDpiNavigationItem(
                                        label = labels.value[index],
                                        icon = requireNotNull(route.icon),
                                        selected = index == 0,
                                        testTag = RipDpiTestTags.bottomNav(route),
                                        onClick = {},
                                    )
                                }.toImmutableList(),
                    ) {}
                }
            }
        }
        labelsByLocale.forEach { localeLabels ->
            listOf(1.15f, 2f).forEach { fontScale ->
                composeRule.runOnIdle {
                    labels.value = localeLabels
                    scale.value = fontScale
                }
                composeRule.waitForIdle()
                localeLabels.forEach { label ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    composeRule
                        .onNodeWithText(label, useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals("$label at ${fontScale}x", 1, layouts.single().lineCount)
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h900dp")
    fun `compact window shows one bottom row of tabs that select and navigate`() {
        val navigated = setNavigation()
        val bounds = tabBounds()

        assertTrue("tabs share one row: $bounds", bounds.all { it.top == bounds.first().top })
        assertTrue("tabs keep destination order: $bounds", bounds.zipWithNext().all { (a, b) -> a.left < b.left })
        destinationLabels().forEach { composeRule.onAllNodesWithText(it).assertCountEquals(1) }
        assertSelectionFollowsClicks(navigated)
    }

    @Test
    @Config(qualifiers = "w411dp-h900dp")
    fun `compact window at maximum font keeps every tab named without visible labels`() {
        setNavigation(fontScale = 2f)
        val bounds = tabBounds()

        assertTrue("tabs share one row: $bounds", bounds.all { it.top == bounds.first().top })
        destinationLabels().forEach { label ->
            composeRule.onAllNodesWithText(label).assertCountEquals(0)
            composeRule.onNode(hasContentDescription(label), useUnmergedTree = true).assertExists()
        }
        bounds.forEach { assertTrue("48dp target: $it", it.width >= 48f && it.height >= 48f) }
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun `medium window shows a rail in destination order`() {
        val navigated = setNavigation()
        assertRail()
        assertSelectionFollowsClicks(navigated)
    }

    @Test
    @Config(qualifiers = "w700dp-h400dp")
    fun `medium window at maximum font keeps every rail destination displayed and named`() {
        setNavigation(fontScale = 2f)
        assertRail()
        destinationLabels().forEach { label -> composeRule.onAllNodesWithText(label).assertCountEquals(1) }
    }

    @Test
    @Config(qualifiers = "w1300dp-h900dp")
    fun `large window shows an expanded rail with labels beside icons`() {
        val navigated = setNavigation()
        val bounds = assertRail()

        assertTrue("expanded rail items are wider than tall: $bounds", bounds.all { it.width > it.height })
        assertSelectionFollowsClicks(navigated)
    }

    @Test
    @Config(qualifiers = "w411dp-h900dp")
    fun `no top-level selection hides the navigation`() {
        composeRule.setContent {
            RipDpiTheme { TopLevelNavigationScaffold(selectedRoute = null, onNavigate = {}) {} }
        }
        Route.topLevel.forEach { composeRule.onAllNodesWithTag(RipDpiTestTags.bottomNav(it)).assertCountEquals(0) }
    }

    @Test
    @Config(qualifiers = "w411dp-h900dp")
    fun `reselecting the current tab gives no selection haptic`() {
        val previousStaticMotion = System.getProperty("ripdpi.staticMotion")
        System.setProperty("ripdpi.staticMotion", "false")
        try {
            val haptics = mutableListOf<Int>()
            // A ViewGroup, because Material ripples attach their container to the nearest one.
            val view =
                object : FrameLayout(ApplicationProvider.getApplicationContext()) {
                    override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                        haptics += feedbackConstant
                        return true
                    }
                }
            composeRule.setContent {
                var selected by androidx.compose.runtime.remember { mutableStateOf<Route>(Route.Home) }
                RipDpiTheme {
                    CompositionLocalProvider(LocalView provides view) {
                        TopLevelNavigationScaffold(selectedRoute = selected, onNavigate = { selected = it }) {}
                    }
                }
            }
            composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Home)).performClick()
            composeRule.runOnIdle { assertEquals(emptyList<Int>(), haptics) }
            composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Config)).performClick()
            composeRule.runOnIdle { assertEquals(1, haptics.size) }
        } finally {
            previousStaticMotion?.let { System.setProperty("ripdpi.staticMotion", it) }
                ?: System.clearProperty("ripdpi.staticMotion")
        }
    }

    private fun setNavigation(fontScale: Float = 1f): MutableList<Route> {
        val navigated = mutableListOf<Route>()
        composeRule.setContent {
            var selected by androidx.compose.runtime.remember { mutableStateOf<Route>(Route.Home) }
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                RipDpiTheme {
                    TopLevelNavigationScaffold(
                        selectedRoute = selected,
                        onNavigate = {
                            selected = it
                            navigated += it
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        return navigated
    }

    private fun assertRail(): List<Rect> {
        val bounds = tabBounds()
        assertTrue("rail items share one column: $bounds", bounds.all { it.left == bounds.first().left })
        assertTrue("rail keeps destination order: $bounds", bounds.zipWithNext().all { (a, b) -> a.top < b.top })
        Route.topLevel.forEach { composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(it)).assertIsDisplayed() }
        return bounds
    }

    private fun tabBounds(): List<Rect> =
        Route.topLevel.map { route ->
            composeRule
                .onNodeWithTag(RipDpiTestTags.bottomNav(route))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .fetchSemanticsNode()
                .boundsInRoot
        }

    private fun assertSelectionFollowsClicks(navigated: List<Route>) {
        composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Home)).assertIsSelected()
        Route.topLevel.reversed().forEach { route ->
            composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(route)).performClick().assertIsSelected()
            Route.topLevel.filter { it != route }.forEach {
                composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(it)).assertIsNotSelected()
            }
            assertEquals(route, navigated.last())
        }
    }

    private fun localizedLabels(languageTag: String): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration =
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(languageTag)) }
        val localized = context.createConfigurationContext(configuration)
        return Route.topLevel.map { route -> localized.getString(route.titleRes) }
    }

    private fun destinationLabels(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Route.topLevel.map { route -> context.getString(route.titleRes) }
    }
}
