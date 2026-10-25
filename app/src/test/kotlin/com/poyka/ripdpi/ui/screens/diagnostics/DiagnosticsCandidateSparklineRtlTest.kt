package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
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
class DiagnosticsCandidateSparklineRtlTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `success fill starts at the left edge in LTR`() {
        assertFill(LayoutDirection.Ltr, leftFilled = true)
    }

    @Test
    fun `success fill starts at the right edge in RTL`() {
        assertFill(LayoutDirection.Rtl, leftFilled = false)
    }

    private fun assertFill(
        direction: LayoutDirection,
        leftFilled: Boolean,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.testTag(Tag)) {
                    // One of four targets succeeded: a quarter of the bar is filled.
                    CandidateSparkline(succeeded = 1, total = 4, color = Color.Black)
                }
            }
        }
        val bitmap = composeRule.onNodeWithTag(Tag).captureToImage()
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        val row = bitmap.height / 2 * bitmap.width
        // The filled part is solid black; the track is translucent, so it is lighter.
        val leftBlue = pixels[row + 1] and 0xFF
        val rightBlue = pixels[row + bitmap.width - 2] and 0xFF
        assertTrue(
            "$direction: left blue $leftBlue, right blue $rightBlue",
            if (leftFilled) leftBlue < rightBlue else rightBlue < leftBlue,
        )
    }

    private companion object {
        const val Tag = "candidate-sparkline"
    }
}
