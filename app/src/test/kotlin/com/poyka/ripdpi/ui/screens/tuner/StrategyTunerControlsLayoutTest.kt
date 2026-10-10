package com.poyka.ripdpi.ui.screens.tuner

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
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
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w420dp-h900dp-mdpi")
class StrategyTunerControlsLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `complete progress and run action fit the normal screen`() {
        verifyControls(running = false)
    }

    @Test
    fun `running progress and cancel action fit the normal screen`() {
        verifyControls(running = true)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h900dp-mdpi")
    fun `complete progress and run action fit large Arabic text`() {
        verifyControls(running = false, width = 320, fontScale = 2f, direction = LayoutDirection.Rtl)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h900dp-mdpi")
    fun `running progress and cancel action fit large Arabic text`() {
        verifyControls(running = true, width = 320, fontScale = 2f, direction = LayoutDirection.Rtl)
    }

    private fun verifyControls(
        running: Boolean,
        width: Int = 420,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        var runs = 0
        var cancels = 0
        val state =
            previewStrategyTunerUiState().let {
                if (running) it.copy(runState = StrategyTunerRunState.Running) else it
            }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
                LocalInspectionMode provides true,
            ) {
                RipDpiTheme {
                    Box(Modifier.requiredSize(width.dp, 900.dp)) {
                        StrategyTunerScreen(
                            state = state,
                            onBack = {},
                            onDomainsChanged = {},
                            onRun = { runs++ },
                            onCancel = { cancels++ },
                            onApply = {},
                        )
                    }
                }
            }
        }
        val action = text(if (running) R.string.strategy_tuner_cancel else R.string.strategy_tuner_run)
        val status =
            RuntimeEnvironment.getApplication().getString(
                R.string.strategy_tuner_status_format,
                state.results.size,
                state.totalExpectedResults,
            )
        capture("${if (running) "running" else "complete"}-$width-$fontScale")
        assertReadable(status, maxLines = if (fontScale == 1f) 1 else 3)
        assertReadable(action, maxLines = if (fontScale == 1f) 1 else 2)
        composeRule.onNodeWithText(action).performClick()
        assertEquals(if (running) 0 else 1, runs)
        assertEquals(if (running) 1 else 0, cancels)
    }

    private fun assertReadable(
        label: String,
        maxLines: Int,
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNodeWithText(label, useUnmergedTree = true)
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("Too many lines for $label: ${layout.lineCount}", layout.lineCount <= maxLines)
        val contentLeft = (0 until layout.lineCount).minOf(layout::getLineLeft)
        val contentTop = (0 until layout.lineCount).minOf(layout::getLineTop)
        (0 until layout.lineCount).forEach { line ->
            assertTrue(
                "Horizontal clipping for $label",
                (layout.getLineRight(line) - contentLeft).roundToInt() <= layout.size.width,
            )
            assertTrue(
                "Vertical clipping for $label",
                (layout.getLineBottom(line) - contentTop).roundToInt() <= layout.size.height,
            )
            assertFalse("Ellipsis for $label", layout.isLineEllipsized(line))
        }
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Text height clipped for $label", layout.size.height <= bounds.height.roundToInt())
        assertTrue("Text width clipped for $label", layout.size.width <= bounds.width.roundToInt())
        assertTrue(
            "Text outside viewport for $label",
            viewport.contains(bounds.topLeft) && viewport.contains(bounds.bottomRight),
        )
    }

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private fun capture(name: String) {
        val file = File("build/ui-quality/tuner-progress/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use {
            composeRule
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
