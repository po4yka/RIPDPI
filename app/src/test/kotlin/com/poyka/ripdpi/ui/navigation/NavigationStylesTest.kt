@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

package com.poyka.ripdpi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.style.CommonStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.EnableKeyboardInput
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.LocalRipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiMotion
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.rdsBackground
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
@Config(sdk = [35], qualifiers = "w420dp-h1200dp")
class NavigationStylesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `keyboard focus paints the bottom tab outline`() {
        var outline = Color.Unspecified
        composeRule.setContent {
            NavigationTestTheme {
                EnableKeyboardInput()
                outline = RipDpiThemeTokens.colors.outline
                BottomNavBar(Route.Home, {}, Modifier.requiredWidth(400.dp))
            }
        }
        val tab = composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Settings))
        val before = countColor(tab.captureToImage(), outline)
        tab.requestFocus().assertIsFocused()
        val after = countColor(tab.captureToImage(), outline)
        assertTrue("Focus must paint the tab border", after > before + 20)
    }

    @Test
    fun `cancelled bottom tab press restores paint and top edge still selects once`() {
        var selected by mutableStateOf<Route?>(Route.Home)
        var calls = 0
        composeRule.setContent {
            NavigationTestTheme {
                BottomNavBar(selected, {
                    selected = it
                    calls++
                }, Modifier.requiredWidth(400.dp))
            }
        }
        val tab = composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Settings))
        val before = tab.captureToImage().toPixelMap()
        tab.performTouchInput { down(center) }
        val pressed = tab.captureToImage().toPixelMap()
        assertTrue("Press must change the painted content", pixelsDiffer(before, pressed))
        tab.performTouchInput { cancel() }
        assertTrue("Cancel must restore content paint", !pixelsDiffer(before, tab.captureToImage().toPixelMap()))
        composeRule.runOnIdle { assertEquals(0, calls) }
        tab.performTouchInput { click(Offset(center.x, 2f)) }
        tab.assertIsSelected()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun `bottom container override changes paint without removing tab actions`() {
        var calls = 0
        composeRule.setContent {
            NavigationTestTheme {
                BottomNavBar(
                    Route.Home,
                    { calls++ },
                    Modifier.requiredWidth(400.dp).testTag(Shell),
                    visualStyle = CommonStyle { rdsBackground(Color.Blue) },
                )
            }
        }
        assertEquals(Color.Blue, composeRule.onNodeWithTag(Shell).captureToImage().toPixelMap()[2, 6])
        composeRule.onNodeWithTag(RipDpiTestTags.bottomNav(Route.Settings)).performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun `rail container override keeps selected tab semantics and real touch action`() {
        var selected by mutableStateOf<Route?>(Route.Home)
        var calls = 0
        composeRule.setContent {
            NavigationTestTheme {
                Box(Modifier.height(600.dp)) {
                    RipDpiNavRail(
                        selected,
                        {
                            selected = it
                            calls++
                        },
                        Modifier.testTag(Shell),
                        visualStyle = CommonStyle { rdsBackground(Color.Blue) },
                    )
                }
            }
        }
        assertEquals(Color.Blue, composeRule.onNodeWithTag(Shell).captureToImage().toPixelMap()[2, 80])
        val settings = composeRule.onNodeWithContentDescription("Settings")
        settings.performTouchInput { click(Offset(center.x, 2f)) }
        settings.assertIsSelected()
        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun `cached rail recipe reads the current theme after a provider update`() {
        var theme by mutableStateOf("light")
        var expected = Color.Unspecified
        composeRule.setContent {
            NavigationTestTheme(theme) {
                expected =
                    RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.navigation.bottomBar).container
                Box(Modifier.height(600.dp)) {
                    RipDpiNavRail(Route.Home, {}, Modifier.testTag(Shell))
                }
            }
        }
        val light = composeRule.onNodeWithTag(Shell).captureToImage().toPixelMap()[2, 80]
        assertEquals(expected, light)
        composeRule.runOnIdle { theme = "dark" }
        val dark = composeRule.onNodeWithTag(Shell).captureToImage().toPixelMap()[2, 80]
        assertEquals(expected, dark)
        assertTrue("Theme change must update the cached shell fill", light != dark)
    }

    private fun countColor(
        image: androidx.compose.ui.graphics.ImageBitmap,
        color: Color,
    ): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] == color) count++
            }
        }
        return count
    }

    private fun pixelsDiffer(
        first: androidx.compose.ui.graphics.PixelMap,
        second: androidx.compose.ui.graphics.PixelMap,
    ): Boolean {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first[x, y] != second[x, y]) return true
            }
        }
        return false
    }

    private companion object {
        const val Shell = "navigation-shell"
    }
}

@Composable
private fun NavigationTestTheme(
    themePreference: String = "light",
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(1f)) {
        RipDpiTheme(themePreference = themePreference) {
            CompositionLocalProvider(
                LocalRipDpiMotion provides RipDpiMotion(animationsEnabled = false),
                content = content,
            )
        }
    }
}
