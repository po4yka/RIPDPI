@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.disabled
import androidx.compose.foundation.style.pressed
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.selected
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiStylesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `caller state layers override defaults that resolve state on layer zero`() {
        lateinit var mutableStyleState: MutableStyleState
        val defaults =
            CommonStyle {
                val color =
                    when {
                        !state.isEnabled -> Color.Gray
                        state.isSelected -> Color.Yellow
                        state.isPressed -> Color.Green
                        else -> Color.Red
                    }
                rdsBackground(color)
            }
        var caller by mutableStateOf(CommonStyle { rdsBackground(Color.Blue) })
        composeRule.setContent {
            mutableStyleState = remember { MutableStyleState(null) }
            val resolver = rememberRipDpiStyleResolver(defaults, caller, mutableStyleState)
            Box(Modifier.size(80.dp).testTag(Surface).ripDpiStyleSurface(resolver))
        }
        assertPixel(Surface, Color.Blue)
        composeRule.runOnIdle { mutableStyleState.isPressed = true }
        assertPixel(Surface, Color.Blue)
        composeRule.runOnIdle { mutableStyleState.isSelected = true }
        assertPixel(Surface, Color.Blue)
        composeRule.runOnIdle { mutableStyleState.isEnabled = false }
        assertPixel(Surface, Color.Blue)
        composeRule.runOnIdle {
            caller =
                CommonStyle {
                    rdsBackground(Color.Blue)
                    pressed { rdsBackground(Color.Magenta) }
                    selected { disabled { rdsBackground(Color.Cyan) } }
                }
        }
        assertPixel(Surface, Color.Cyan)
        composeRule.runOnIdle { mutableStyleState.isEnabled = true }
        assertPixel(Surface, Color.Magenta)
    }

    @Test
    fun `static style reads updated theme local at the attached node`() {
        var color by mutableStateOf(Color.Red)
        val style = CommonStyle { rdsBackground(LocalStyleColor.currentValue) }
        composeRule.setContent {
            CompositionLocalProvider(LocalStyleColor provides color) {
                val state = rememberUpdatedStyleState(null)
                val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
                Box(Modifier.size(80.dp).testTag(Surface).ripDpiStyleSurface(resolver))
            }
        }
        assertPixel(Surface, Color.Red)
        composeRule.runOnIdle { color = Color.Blue }
        assertPixel(Surface, Color.Blue)
    }

    @Test
    fun `shared interaction source handles cancellation and disabled clicks`() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        val style =
            CommonStyle {
                rdsBackground(
                    when {
                        !state.isEnabled -> Color.Gray
                        state.isPressed -> Color.Red
                        else -> Color.Blue
                    },
                )
            }
        composeRule.setContent {
            val source = remember { MutableInteractionSource() }
            val state = rememberUpdatedStyleState(source) { it.isEnabled = enabled }
            val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
            Box(
                Modifier
                    .size(80.dp)
                    .testTag(Surface)
                    .ripDpiStyleSurface(resolver)
                    .clickable(source, indication = null, enabled = enabled) { clicks++ },
            )
        }
        val surface = composeRule.onNodeWithTag(Surface)
        surface.performTouchInput { down(center) }
        assertPixel(Surface, Color.Red)
        surface.performTouchInput { cancel() }
        assertPixel(Surface, Color.Blue)
        composeRule.runOnIdle {
            assertEquals(0, clicks)
            enabled = false
        }
        surface.assertIsNotEnabled().performClick()
        assertPixel(Surface, Color.Gray)
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun `content scale preserves the shell and outer hit target`() {
        var clicks = 0
        var scale by mutableStateOf(1f)
        val style =
            CommonStyle {
                rdsBackground(Color.Blue)
                rdsContentScale(scale)
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val source = remember { MutableInteractionSource() }
                val state = rememberUpdatedStyleState(source)
                val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
                Box(
                    Modifier
                        .size(80.dp)
                        .testTag(Surface)
                        .ripDpiStyleSurface(resolver)
                        .clickable(source, indication = null) { clicks++ },
                ) {
                    Box(Modifier.fillMaxSize().ripDpiStyleContent(resolver).background(Color.Red))
                }
            }
        }
        val surface = composeRule.onNodeWithTag(Surface)
        surface.assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp)
        assertPixel(Surface, Color.Red, x = 0.1f)
        composeRule.runOnIdle { scale = 0.5f }
        surface.assertWidthIsEqualTo(80.dp).assertHeightIsEqualTo(80.dp)
        assertPixel(Surface, Color.Red)
        assertPixel(Surface, Color.Blue, x = 0.1f)
        surface.performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(2f, 2f),
            )
        }
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `style minimum height expands content and retains larger content`() {
        var contentHeight by mutableStateOf(12.dp)
        val style = CommonStyle { rdsMinHeight(48.dp) }
        composeRule.setContent {
            val state = rememberUpdatedStyleState(null)
            val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
            Box(Modifier.testTag(Surface).ripDpiStyleSurface(resolver)) {
                Box(Modifier.size(width = 20.dp, height = contentHeight))
            }
        }
        val surface = composeRule.onNodeWithTag(Surface)
        surface.assertHeightIsEqualTo(48.dp)
        composeRule.runOnIdle { contentHeight = 64.dp }
        surface.assertHeightIsEqualTo(64.dp)
    }

    @Test
    fun `logical content padding measures and places correctly in RTL`() {
        val style = CommonStyle { rdsContentPadding(start = 8.dp, top = 4.dp, end = 20.dp, bottom = 6.dp) }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                val state = rememberUpdatedStyleState(null)
                val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
                Box(Modifier.testTag(Surface).ripDpiStyleSurface(resolver)) {
                    Box(Modifier.ripDpiStyleContent(resolver, applyPadding = true)) {
                        Box(Modifier.size(10.dp).testTag("style-content"))
                    }
                }
            }
        }
        val surface = composeRule.onNodeWithTag(Surface)
        surface.assertWidthIsEqualTo(38.dp).assertHeightIsEqualTo(20.dp)
        val outer = surface.fetchSemanticsNode().boundsInRoot
        val child = composeRule.onNodeWithTag("style-content").fetchSemanticsNode().boundsInRoot
        assertEquals(20f, child.left - outer.left, 0.1f)
        assertEquals(4f, child.top - outer.top, 0.1f)
    }

    @Test
    fun `cached button recipe follows theme and contrast when animations are disabled`() {
        var dark by mutableStateOf(false)
        var contrast by mutableStateOf(RipDpiContrastLevel.Standard)
        val style = RipDpiComponentStyles.button(RipDpiButtonStateRole.Primary)
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                RipDpiTheme(themePreference = if (dark) "dark" else "light", contrastLevel = contrast) {
                    CompositionLocalProvider(LocalRipDpiMotion provides RipDpiMotion(animationsEnabled = false)) {
                        val state = rememberUpdatedStyleState(null) { it.isEnabled = false }
                        val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
                        Box(Modifier.size(80.dp).testTag(Surface).ripDpiStyleSurface(resolver))
                    }
                }
            }
        }
        assertPixel(Surface, LightRipDpiExtendedColors.border)
        composeRule.runOnIdle { dark = true }
        assertPixel(Surface, DarkRipDpiExtendedColors.border)
        composeRule.runOnIdle { contrast = RipDpiContrastLevel.High }
        assertPixel(Surface, DarkRipDpiExtendedColors.adjustForContrast(RipDpiContrastLevel.High, isDark = true).border)
    }

    @Test
    fun `button visual override cannot shrink its target or enable loading actions`() {
        var loading by mutableStateOf(false)
        var clicks = 0
        val caller =
            CommonStyle {
                rdsBackground(Color.Blue)
                rdsMinHeight(1.dp)
                rdsContentPadding()
                rdsContentScale(0.5f)
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(1f)) {
                RipDpiTheme(themePreference = "light") {
                    RipDpiButton(
                        text = "Go",
                        onClick = { clicks++ },
                        modifier = Modifier.width(80.dp).testTag(Surface),
                        loading = loading,
                        visualStyle = caller,
                    )
                }
            }
        }
        val button = composeRule.onNodeWithTag(Surface)
        button.assertHeightIsEqualTo(48.dp).assertWidthIsEqualTo(80.dp)
        assertPixel(Surface, Color.Blue, x = 0.1f)
        button.performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(4f, 24f),
            )
        }
        composeRule.runOnIdle {
            assertEquals(1, clicks)
            loading = true
        }
        button.assertIsNotEnabled().performClick()
        button.assertHeightIsEqualTo(48.dp)
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun `numeric rounded shape clips child content and zero width has no border`() {
        val style =
            CommonStyle {
                rdsBackground(Color.Blue)
                rdsBorder(Color.Green, 0.dp)
                rdsCornerRadius(20.dp)
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(80.dp).background(Color.White).testTag(Surface)) {
                    val state = rememberUpdatedStyleState(null)
                    val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
                    Box(Modifier.size(80.dp).ripDpiStyleSurface(resolver, clip = true)) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        assertPixel(Surface, Color.White, x = 0.01f, y = 0.01f)
        assertPixel(Surface, Color.Red)
        assertPixel(Surface, Color.Red, x = 0.5f, y = 0.01f)
    }

    @Test
    fun `background alpha changes only the solid fill and keeps child content opaque`() {
        val style =
            CommonStyle {
                rdsBackground(Color.Blue)
                rdsBackgroundAlpha(0.5f)
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(80.dp).background(Color.White).testTag(Surface)) {
                    val state = rememberUpdatedStyleState(null)
                    val resolver = rememberRipDpiStyleResolver(style, CommonStyle, state)
                    Box(Modifier.size(80.dp).ripDpiStyleSurface(resolver)) {
                        Box(Modifier.size(40.dp).background(Color.Red))
                    }
                }
            }
        }
        assertPixel(Surface, Color.Red, x = 0.1f, y = 0.1f)
        val pixels = composeRule.onNodeWithTag(Surface).captureToImage().toPixelMap()
        val fill = pixels[60, 60]
        assertEquals(0.5f, fill.red, 0.01f)
        assertEquals(0.5f, fill.green, 0.01f)
        assertEquals(1f, fill.blue, 0.01f)
    }

    private fun assertPixel(
        tag: String,
        expected: Color,
        x: Float = 0.5f,
        y: Float = 0.5f,
    ) {
        val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals(expected.toArgb(), pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()].toArgb())
    }

    private companion object {
        const val Surface = "style-surface"
        val LocalStyleColor = staticCompositionLocalOf { Color.Red }
    }
}
