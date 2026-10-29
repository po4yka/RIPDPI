package com.poyka.ripdpi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DynamicColorThemeTest {
    @get:Rule val compose = createComposeRule()

    private data class Observed(
        val scheme: ColorScheme,
        val background: Color,
        val success: Color,
        val warning: Color,
    )

    private fun observe(dynamicColor: Boolean): Observed {
        lateinit var observed: Observed
        compose.setContent {
            RipDpiTheme(themePreference = "light", dynamicColor = dynamicColor) {
                val colors = RipDpiThemeTokens.colors
                observed = Observed(MaterialTheme.colorScheme, colors.background, colors.success, colors.warning)
            }
        }
        compose.waitForIdle()
        return observed
    }

    @Test
    @Config(sdk = [31])
    fun `wallpaper colors drive Material and RDS neutrals while status roles stay fixed`() {
        val dynamic = dynamicLightColorScheme(RuntimeEnvironment.getApplication())
        val observed = observe(dynamicColor = true)
        assertEquals(dynamic.primary, observed.scheme.primary)
        assertEquals(dynamic.surface, observed.background)
        assertEquals(LightRipDpiExtendedColors.success, observed.success)
        assertEquals(LightRipDpiExtendedColors.warning, observed.warning)
    }

    @Test
    @Config(sdk = [30])
    fun `wallpaper colors fall back to the seed scheme below Android 12`() {
        val observed = observe(dynamicColor = true)
        assertEquals(ripDpiLightColorScheme().primary, observed.scheme.primary)
        assertEquals(ripDpiLightColorScheme().surface, observed.background)
    }
}
