package com.poyka.ripdpi.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiIconButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiTextAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbar
import com.poyka.ripdpi.ui.components.feedback.RipDpiSnackbarTone
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], qualifiers = "w420dp-h2200dp")
class SharedReadabilityLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val width = mutableStateOf(420)
    private val scale = mutableStateOf(1f)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    @Test
    fun shortChipsKeepTheirWidthAndSelectedStateKeepsTheSameTextSize() {
        var selected by mutableStateOf(false)
        composeRule.setContent {
            TestContainer {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RipDpiChip(
                        "DNS",
                        { selected = !selected },
                        selected = selected,
                        leadingIcon = RipDpiIcons.Check,
                        wrapLabel = true,
                    )
                    RipDpiChip("TLS", {}, wrapLabel = true)
                    RipDpiChip("Use encrypted DNS for this connection", {}, wrapLabel = true)
                }
            }
        }
        configurations {
            listOf("DNS", "TLS", "Use encrypted DNS for this connection").forEach(::assertCompleteText)
            val before = composeRule.onNodeWithText("DNS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val other = composeRule.onNodeWithText("TLS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Short chips share the first line", kotlin.math.abs(before.top - other.top) <= 1f)
            val oldLayout = textLayout("DNS")
            val oldStyle = oldLayout.layoutInput.style.fontSize
            composeRule.onNodeWithText("DNS").performClick()
            assertEquals(oldStyle, textLayout("DNS").layoutInput.style.fontSize)
            assertEquals(oldLayout.size, textLayout("DNS").size)
        }
    }

    @Test
    fun defaultButtonsKeepLongLabelsAndDisabledCompactActionsDoNotDispatch() {
        var clicks = 0
        var disabledClicks = 0
        var textClicks = 0
        val textLabel = "Завершить проверку с текущими настройками"
        val disabledTextLabel = "إنهاء الاختبار بالإعدادات الحالية"
        val label = "Reconnect using the saved connection configuration"
        composeRule.setContent {
            TestContainer {
                RipDpiButton(
                    label,
                    { clicks++ },
                    leadingIcon = RipDpiIcons.Connected,
                    density = RipDpiControlDensity.Compact,
                )
                RipDpiTextAction(textLabel, { textClicks++ })
                RipDpiTextAction(disabledTextLabel, { disabledClicks++ }, enabled = false)
                RipDpiButton("Disabled action", { disabledClicks++ }, enabled = false)
                RipDpiIconButton(RipDpiIcons.Close, "Close details", {
                    disabledClicks++
                }, enabled = false, density = RipDpiControlDensity.Compact)
            }
        }
        configurations {
            assertCompleteText(label)
            assertCompleteText(textLabel)
            assertCompleteText(disabledTextLabel)
            val textAction = composeRule.onNodeWithText(textLabel).fetchSemanticsNode().boundsInRoot
            assertTrue(textAction.height >= 48f)
            composeRule.onNodeWithText(textLabel).performClick()
            composeRule.onNodeWithText(disabledTextLabel).assertIsNotEnabled().performClick()
            composeRule.onNodeWithText(label).performClick()
            composeRule.onNodeWithText("Disabled action").assertIsNotEnabled().performClick()
            composeRule.onNodeWithContentDescription("Close details").assertIsNotEnabled().performClick()
            val glyph =
                composeRule
                    .onNodeWithContentDescription(
                        "Close details",
                        useUnmergedTree = true,
                    ).fetchSemanticsNode()
            assertTrue("Compact action glyph stays readable", glyph.boundsInRoot.width >= 24f)
        }
        assertEquals(8, clicks)
        assertEquals(8, textClicks)
        assertEquals(0, disabledClicks)
    }

    @Test
    fun snackbarGivesLongActionsTheirOwnLineAndKeepsTheMessageWide() {
        val message = "The saved connection stopped before the tunnel could be established."
        val action = "Open connection settings"
        var clicks = 0
        composeRule.setContent {
            TestContainer {
                RipDpiSnackbar(
                    message,
                    actionLabel = action,
                    onAction = { clicks++ },
                    tone = RipDpiSnackbarTone.Warning,
                )
            }
        }
        configurations {
            assertCompleteText(message)
            assertCompleteText(action)
            val messageBounds =
                composeRule
                    .onNodeWithText(
                        message,
                        useUnmergedTree = true,
                    ).fetchSemanticsNode()
                    .boundsInRoot
            val actionBounds =
                composeRule
                    .onNodeWithText(
                        action,
                        useUnmergedTree = true,
                    ).fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(messageBounds.width >= width.value / 2)
            assertTrue(actionBounds.top >= messageBounds.bottom)
            composeRule.onNodeWithText(action).performClick()
        }
        assertEquals(8, clicks)
    }

    @androidx.compose.runtime.Composable
    private fun TestContainer(content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(
            LocalDensity provides Density(1f, scale.value),
            LocalLayoutDirection provides direction.value,
        ) {
            RipDpiTheme {
                Column(
                    Modifier.requiredWidth(width.value.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { content() }
            }
        }
    }

    private fun configurations(assertion: () -> Unit) {
        listOf(320, 420).forEach { screenWidth ->
            listOf(1f, 2f).forEach { fontScale ->
                LayoutDirection.entries.forEach { layoutDirection ->
                    composeRule.runOnIdle {
                        width.value = screenWidth
                        scale.value = fontScale
                        direction.value =
                            layoutDirection
                    }
                    composeRule.waitForIdle()
                    assertion()
                }
            }
        }
    }

    private fun assertCompleteText(text: String) {
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layout = textLayout(text)
        assertFalse("Text remains complete: $text", layout.hasVisualOverflow)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
