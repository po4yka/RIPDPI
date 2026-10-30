package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.chrome.RipDpiPanelHeader
import com.poyka.ripdpi.ui.components.scaffold.RipDpiSettingsScaffold
import com.poyka.ripdpi.ui.theme.RipDpiIcons
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
@Config(sdk = [35], qualifiers = "ru-rRU-w420dp-h1200dp")
class RipDpiTopAppBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeRussianTitleWrapsCompletelyAtNarrowWidth() {
        assertTitleWrapsCompletely("Восстановление после нехватки памяти", LayoutDirection.Ltr)
    }

    @Test
    @Config(qualifiers = "ar-w420dp-h1200dp")
    fun largeArabicTitleWrapsCompletelyInRtl() {
        assertTitleWrapsCompletely("الاستعادة بعد نفاد الذاكرة في الجهاز", LayoutDirection.Rtl)
    }

    private fun assertTitleWrapsCompletely(
        label: String,
        layoutDirection: LayoutDirection,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
                LocalLayoutDirection provides layoutDirection,
            ) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(320.dp)) {
                        RipDpiTopAppBar(label, navigationIcon = RipDpiIcons.Back, onNavigationClick = {})
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertTrue("A long title at 2x font wraps onto several lines", layout.lineCount > 1)
            assertFalse("The complete title must remain visible", layout.hasVisualOverflow)
            (0 until layout.lineCount).forEach { line -> assertFalse(layout.isLineEllipsized(line)) }
        }
        val title = composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithContentDescription(backLabel()).fetchSemanticsNode().boundsInRoot
        assertTrue("The wrapped title sits below the back button, not beside it", title.top >= back.bottom)
    }

    @Test
    @Config(qualifiers = "w1300dp-h900dp")
    fun wideWindowKeepsTheBackgroundFullWidthAndTheControlsInTheContentColumn() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RipDpiTheme {
                    RipDpiTopAppBar(
                        "Logs",
                        Modifier.testTag("bar"),
                        navigationIcon = RipDpiIcons.Back,
                        onNavigationClick = {},
                        actions = {
                            RipDpiIconButton(
                                icon = RipDpiIcons.Search,
                                contentDescription = "Search",
                                onClick = {},
                            )
                        },
                    )
                }
            }
        }
        val bar = composeRule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithContentDescription(backLabel()).fetchSemanticsNode().boundsInRoot
        val search = composeRule.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        // Expanded windows cap the content column at 960dp, centered in the 1300dp window.
        val columnStart = (1300f - 960f) / 2
        assertEquals(1300f, bar.width, 0.5f)
        assertTrue("Back sits inside the centered column: $back", back.left >= columnStart)
        assertTrue("Search sits inside the centered column: $search", search.right <= 1300f - columnStart)
    }

    @Test
    fun largeTitleWithoutControlsDoesNotReserveAnEmptyToolbarRow() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.settings)
        val width = mutableStateOf(320)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides direction.value,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(width.value.dp)) {
                        RipDpiTopAppBar(label, Modifier.testTag("bar"))
                    }
                }
            }
        }
        listOf(320, 420).forEach { viewport ->
            LayoutDirection.entries.forEach { layoutDirection ->
                composeRule.runOnIdle {
                    width.value = viewport
                    direction.value = layoutDirection
                }
                val bar = composeRule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
                val title =
                    composeRule
                        .onNodeWithText(label)
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertTrue(
                    "No empty controls row above the title",
                    title.top - bar.top < TopAppBarDefaults.TopAppBarExpandedHeight.value,
                )
            }
        }
    }

    @Test
    fun realSettingsScaffoldDoesNotForwardAnEmptyActionSlot() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.settings)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalLayoutDirection provides direction.value,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        RipDpiSettingsScaffold(label, Modifier.testTag("settings-scaffold")) {}
                    }
                }
            }
        }
        LayoutDirection.entries.forEach { layoutDirection ->
            composeRule.runOnIdle { direction.value = layoutDirection }
            val scaffold = composeRule.onNodeWithTag("settings-scaffold").fetchSemanticsNode().boundsInRoot
            val title =
                composeRule
                    .onNodeWithText(label)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(
                "The real Settings title starts without an empty toolbar row",
                title.top - scaffold.top < TopAppBarDefaults.TopAppBarExpandedHeight.value,
            )
        }
    }

    @Test
    fun settingsScaffoldCollapsesTheBarOnScrollAndKeepsTheTitleReadable() {
        val label = "Восстановление после нехватки памяти"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp).requiredHeight(900.dp)) {
                        RipDpiSettingsScaffold(
                            label,
                            Modifier.testTag("settings-scaffold"),
                            navigationIcon = RipDpiIcons.Back,
                            onNavigationClick = {},
                        ) {
                            items(60) { index -> Text("Row $index") }
                        }
                    }
                }
            }
        }
        val expanded = composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithContentDescription(backLabel()).fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded at 2x, the title has its own row", expanded.top >= back.bottom)

        composeRule.onNodeWithTag("settings-scaffold").performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        // The merged tree is what TalkBack reads; Material clears the hidden expanded title from it.
        val collapsed =
            composeRule
                .onNodeWithText(label)
                .assertIsDisplayed()
                .fetchSemanticsNode()
        assertTrue("The scroll behavior collapsed the title row", collapsed.boundsInRoot.top < expanded.top)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(label)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        layouts.forEach { layout ->
            (0 until layout.lineCount).forEach { line -> assertFalse(layout.isLineEllipsized(line)) }
        }
    }

    @Test
    fun backButtonKeepsItsLabelAndRole() {
        var clicks = 0
        composeRule.setContent {
            RipDpiTheme {
                RipDpiTopAppBar(
                    "Logs",
                    navigationIcon = RipDpiIcons.Back,
                    onNavigationClick = { clicks++ },
                )
            }
        }
        composeRule
            .onNodeWithContentDescription(backLabel())
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()
        assertEquals(1, clicks)
    }

    private fun backLabel(): String = RuntimeEnvironment.getApplication().getString(R.string.navigation_back)

    @Test
    fun explicitNavigationAndRowScopeActionStayReachableAtNormalAndLargeFont() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.settings)
        val scale = mutableStateOf(1f)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val enabled = mutableStateOf(true)
        var backClicks = 0
        var actionClicks = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale.value),
                LocalLayoutDirection provides direction.value,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        RipDpiTopAppBar(
                            label,
                            navigationIcon = RipDpiIcons.Back,
                            onNavigationClick = { backClicks++ },
                            navigationContentDescription = "Back",
                            navigationEnabled = enabled.value,
                            actions = {
                                RipDpiIconButton(
                                    RipDpiIcons.Config,
                                    "Options",
                                    { actionClicks++ },
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                            },
                        )
                    }
                }
            }
        }
        listOf(1f, 2f).forEach { fontScale ->
            LayoutDirection.entries.forEach { layoutDirection ->
                composeRule.runOnIdle {
                    scale.value = fontScale
                    direction.value = layoutDirection
                }
                listOf("Back", "Options").forEach { description ->
                    val bounds = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                    assertTrue("Controls keep a minimum 48dp touch target", bounds.width >= 48f && bounds.height >= 48f)
                }
                composeRule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
                composeRule.onNodeWithContentDescription("Options").assertIsDisplayed().performClick()
            }
        }
        assertEquals(4, backClicks)
        assertEquals(4, actionClicks)
        composeRule.runOnIdle { enabled.value = false }
        composeRule.onNodeWithContentDescription("Back").assertIsNotEnabled().performClick()
        assertEquals(4, backClicks)
    }

    @Test
    fun screenTitleAndSharedSectionHeadersAreTalkBackHeadings() {
        val scale = mutableStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                RipDpiTheme {
                    Column(Modifier.requiredWidth(320.dp)) {
                        RipDpiTopAppBar("Diagnostics", navigationIcon = RipDpiIcons.Back, onNavigationClick = {})
                        SettingsCategoryHeader("Network")
                        RipDpiPanelHeader("Probe results", supporting = "Last run")
                    }
                }
            }
        }
        // Font scale 2 moves the title to the two-row bar's expanded row; both layouts must keep the heading.
        listOf(1f, 2f).forEach { fontScale ->
            composeRule.runOnIdle { scale.value = fontScale }
            listOf("Diagnostics", "NETWORK", "Probe results").forEach { heading ->
                composeRule.onNodeWithText(heading, useUnmergedTree = true).assert(isHeading())
            }
            composeRule.onNodeWithText("Last run", useUnmergedTree = true).assert(!isHeading())
        }
    }

    @Test
    fun maximumFontPlacesControlsAboveCompleteTitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                RipDpiTheme {
                    Box(modifier = Modifier.requiredWidth(411.dp)) {
                        RipDpiTopAppBar(
                            title = "Diagnostics",
                            actions = {
                                RipDpiIconButton(
                                    icon = RipDpiIcons.NetworkCheck,
                                    contentDescription = "Connection health",
                                    onClick = {},
                                )
                                RipDpiIconButton(
                                    icon = RipDpiIcons.Config,
                                    contentDescription = "Options",
                                    onClick = {},
                                )
                                RipDpiIconButton(
                                    icon = RipDpiIcons.Logs,
                                    contentDescription = "History",
                                    onClick = {},
                                )
                            },
                        )
                    }
                }
            }
        }

        val title = composeRule.onNodeWithText("Diagnostics").assertIsDisplayed().fetchSemanticsNode()
        val actions =
            listOf("Connection health", "Options", "History").map { label ->
                composeRule.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
            }

        assertTrue(actions.all { action -> action.boundsInRoot.bottom <= title.boundsInRoot.top })
        assertTrue(actions.all { action -> action.boundsInRoot.right <= 411f })
    }
}
