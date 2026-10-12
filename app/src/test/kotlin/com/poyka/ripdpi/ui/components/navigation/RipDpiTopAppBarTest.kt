package com.poyka.ripdpi.ui.components.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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
@Config(sdk = [35], qualifiers = "ru-rRU")
class RipDpiTopAppBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeRussianTitleRemainsCompleteAtNarrowWidth() {
        val label = "Восстановление после нехватки памяти"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
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
            assertFalse("The complete title must remain visible", layout.hasVisualOverflow)
            (0 until layout.lineCount).forEach { line -> assertFalse(layout.isLineEllipsized(line)) }
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
