package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import kotlinx.collections.immutable.persistentListOf
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
@Config(sdk = [35], qualifiers = "w420dp-h1800dp")
class RipDpiDegradationStripTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `large font retains complete metric values and reachable actions below content`() =
        assertResponsiveContent(fontScale = 2f, direction = LayoutDirection.Ltr, value = "204 kB")

    @Test
    @Config(qualifiers = "ar-rEG-w420dp-h1800dp")
    fun `RTL large font retains complete metric values and reachable actions`() =
        assertResponsiveContent(fontScale = 2f, direction = LayoutDirection.Rtl, value = "٢٠٤ كيلوبايت")

    @Test
    fun `normal font keeps complete content and working primary and secondary actions`() =
        assertResponsiveContent(fontScale = 1f, direction = LayoutDirection.Ltr, value = "204 kB")

    private fun assertResponsiveContent(
        fontScale: Float,
        direction: LayoutDirection,
        value: String,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val title = context.getString(R.string.home_quality_measurements_title)
        val body = context.getString(R.string.home_quality_waiting)
        val label = context.getString(R.string.home_quality_traffic_total)
        val primary = context.getString(R.string.vpn_quality_strip_reprobe)
        var primaryClicks = 0
        var secondaryClicks = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                RipDpiTheme {
                    RipDpiDegradationStrip(
                        title,
                        body,
                        persistentListOf(RipDpiDegradationMetric(label, value, "", false)),
                        sinceLabel = context.getString(R.string.measurement_byte_total),
                        primaryAction = RipDpiDegradationAction(primary) { primaryClicks++ },
                        secondaryAction = RipDpiDegradationAction("Close") { secondaryClicks++ },
                        modifier = Modifier.requiredWidth(390.dp).ripDpiTestTag("measurement-strip"),
                        tone = RipDpiDegradationTone.Nominal,
                    )
                }
            }
        }
        val bounds = composeRule.onNodeWithTag("measurement-strip").fetchSemanticsNode().boundsInRoot
        listOf(title, body, label, value, primary, "Close").forEach {
            val textBounds = assertCompleteText(it)
            assertTrue(
                "Complete text '$it' stays inside the card",
                bounds.contains(textBounds.topLeft) && bounds.contains(textBounds.bottomRight),
            )
        }
        listOf(primary, "Close").forEach {
            val action = composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "The entire '$it' hit target stays inside the card",
                bounds.contains(action.topLeft) && bounds.contains(action.bottomRight),
            )
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val valueNode =
            composeRule
                .onNodeWithText(value, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                .fetchSemanticsNode()
        assertEquals("The aggregate value must fit without character wrapping", 1, layouts.single().lineCount)
        assertTrue(valueNode.boundsInRoot.left >= bounds.left && valueNode.boundsInRoot.right <= bounds.right)
        val actionBounds = composeRule.onNodeWithText(primary).fetchSemanticsNode().boundsInRoot
        assertTrue(actionBounds.top >= valueNode.boundsInRoot.bottom)
        composeRule.onNodeWithText(primary).performClick()
        composeRule.onNodeWithText("Close").performClick()
        assertEquals(1, primaryClicks)
        assertEquals(1, secondaryClicks)
    }

    private fun assertCompleteText(text: String): Rect {
        val layouts = mutableListOf<TextLayoutResult>()
        val node =
            composeRule
                .onNodeWithText(text, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                .fetchSemanticsNode()
        val layout = layouts.single()
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
        assertTrue(
            "Text '$text' width=${layout.size.width}, paragraph=${layout.multiParagraph.width}, " +
                "node=${node.boundsInRoot}, constraints=${layout.layoutInput.constraints}; lines=" +
                (0 until layout.lineCount).map { "${layout.getLineLeft(it)}..${layout.getLineRight(it)}" },
            (0 until layout.lineCount).all {
                layout.getLineLeft(it) >= -1f &&
                    layout.getLineRight(it) <= layout.size.width + 1f
            },
        )
        return node.boundsInRoot
    }
}
