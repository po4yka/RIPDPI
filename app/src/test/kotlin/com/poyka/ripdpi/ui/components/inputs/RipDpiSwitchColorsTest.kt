package com.poyka.ripdpi.ui.components.inputs

import androidx.compose.material3.SwitchColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.ui.theme.DarkRipDpiExtendedColors
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiSwitchColorsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `dark switches keep the off state visible and the on track ink`() {
        lateinit var switch: SwitchColors
        composeRule.setContent { RipDpiTheme(themePreference = "dark") { switch = ripDpiSwitchColors() } }
        val dark = DarkRipDpiExtendedColors
        composeRule.runOnIdle {
            assertEquals(dark.foreground, switch.checkedTrackColor)
            assertEquals(dark.background, switch.checkedThumbColor)
            // WCAG 1.4.11: 3:1 for the off-track outline against the card and for the thumb against its track.
            assertTrue(contrast(switch.uncheckedBorderColor, dark.card) >= 3f)
            assertTrue(contrast(switch.uncheckedThumbColor, switch.uncheckedTrackColor) >= 3f)
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
