@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.inputs.RipDpiChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w600dp-h600dp-xxxhdpi")
class RipDpiSpringParityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `chip interrupted painted scale and radius match typed legacy springs`() {
        val pressed = mutableStateOf(false)
        lateinit var radius: State<Dp>
        lateinit var scale: State<Float>
        show {
            val motion = RipDpiThemeTokens.motion
            val state = RipDpiThemeTokens.state.chip.resolve(selected = true, enabled = true, isPressed = pressed.value)
            scale = animateFloatAsState(state.scale, motion.motionAwareSpring(expressive = true), label = "legacyScale")
            radius = animateDpAsState(state.cornerRadius, motion.motionAwareSpring(), label = "legacyRadius")
            Column {
                RipDpiChip(
                    text = Label,
                    onClick = {},
                    selected = true,
                    leadingIcon = null,
                    modifier = Modifier.size(200.dp, 64.dp).testTag(Actual),
                    visualStyle = blueShell,
                )
                ReferenceShell(radius, scale)
            }
        }
        composeRule.onNodeWithTag(Actual).performTouchInput { down(center) }
        composeRule.runOnIdle { pressed.value = true }
        advance(64)
        assertTrue(
            "Forward control must animate the press",
            abs(scale.value - DefaultRipDpiMotion.selectionScale) > 0.001f,
        )
        assertCornerMatchesReference()
        composeRule.onNodeWithTag(Actual).performTouchInput { up() }
        composeRule.runOnIdle { pressed.value = false }
        repeat(8) {
            advance(16)
            assertCornerMatchesReference()
        }
        advance(500)
        assertCornerMatchesReference()
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            RipDpiTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(4f),
                    LocalRipDpiMotion provides RipDpiMotion(animationsEnabled = true, hapticsEnabled = false),
                ) { content() }
            }
        }
        advance(500)
    }

    @Composable
    private fun ReferenceShell(
        radius: State<Dp>,
        scale: State<Float>?,
    ) {
        Box(Modifier.size(200.dp, 64.dp).testTag(Reference)) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val currentScale = scale?.value ?: 1f
                        scaleX = currentScale
                        scaleY = currentScale
                    }.drawWithContent {
                        val r = radius.value.toPx()
                        drawRoundRect(Color.Blue, cornerRadius = CornerRadius(r, r))
                    },
            )
        }
    }

    private fun advance(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private fun assertCornerMatchesReference() {
        val actual = composeRule.onNodeWithTag(Actual).captureToImage().toPixelMap()
        val expected = composeRule.onNodeWithTag(Reference).captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        // The label sits in the middle. These rows cover the corner and the painted shell edge.
        for (y in listOf(12, 28, 48)) {
            fun edge(pixels: androidx.compose.ui.graphics.PixelMap): Int =
                (0 until pixels.width / 3).firstOrNull { x ->
                    val color = pixels[x, y]
                    color.blue > 0.8f && color.red < 0.2f && color.alpha > 0.8f
                } ?: error("Missing blue shell on row $y")
            assertTrue("Spring shell edge differs on row $y", abs(edge(actual) - edge(expected)) <= 1)
        }
    }

    private companion object {
        const val Actual = "spring-actual"
        const val Reference = "spring-reference"
        const val Label = "Spring"
        val blueShell =
            CommonStyle {
                rdsBackground(Color.Blue)
                rdsBorder(Color.Transparent, 0.dp)
            }
    }
}
