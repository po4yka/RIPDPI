@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
class RipDpiStyleMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `same animated recipe interpolates a changed snapshot color`() {
        var color by mutableStateOf(Color.Red)
        val style = animatedFill { color }
        showSurface(defaultStyle = { style })
        assertContinuousColorTransition { color = Color.Blue }
    }

    @Test
    fun `replacing an animated default recipe interpolates from the displayed color`() {
        var style by mutableStateOf(animatedFill { Color.Red })
        showSurface(defaultStyle = { style })
        assertContinuousColorTransition { style = animatedFill { Color.Blue } }
    }

    @Test
    fun `replacing an animated caller recipe interpolates from the displayed color`() {
        val defaults = CommonStyle { rdsBackground(Color.Green) }
        var caller by mutableStateOf(animatedFill { Color.Red })
        showSurface(defaultStyle = { defaults }, visualStyle = { caller })
        assertContinuousColorTransition { caller = animatedFill { Color.Blue } }
    }

    private fun showSurface(
        defaultStyle: @Composable () -> CommonStyle,
        visualStyle: @Composable () -> CommonStyle = { CommonStyle },
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalRipDpiMotion provides RipDpiMotion(animationsEnabled = true, hapticsEnabled = false),
            ) {
                val source = remember { MutableInteractionSource() }
                val state = rememberUpdatedStyleState(source)
                val resolver = rememberRipDpiStyleResolver(defaultStyle(), visualStyle(), state)
                Box(Modifier.size(80.dp).testTag(Surface).ripDpiStyleSurface(resolver))
            }
        }
        // Finish any first-display work before observing a subsequent transition.
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun assertContinuousColorTransition(change: () -> Unit) {
        assertEquals(Color.Red.toArgb(), centerPixel().toArgb())
        composeRule.runOnIdle(change)
        // Android draw/observer work is not driven by the test clock. Let Styles collect
        // snapshot changes before advancing, and draw a replacement recipe after composition.
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(64L)
        val intermediate = centerPixel()
        assertTrue(
            "Expected an intermediate red-to-blue color, observed $intermediate",
            intermediate.red > 0.05f && intermediate.red < 0.98f &&
                intermediate.blue > 0.05f && intermediate.blue < 0.98f,
        )
        composeRule.mainClock.advanceTimeBy(500L)
        assertEquals(Color.Blue.toArgb(), centerPixel().toArgb())
    }

    private fun centerPixel(): Color {
        composeRule.waitForIdle()
        val pixels = composeRule.onNodeWithTag(Surface).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun animatedFill(color: () -> Color): CommonStyle =
        CommonStyle {
            val motion = LocalRipDpiMotion.currentValue
            animate(motion.stateTween<Float>()) { rdsBackground(color()) }
        }

    private companion object {
        const val Surface = "style-motion-surface"
    }
}
