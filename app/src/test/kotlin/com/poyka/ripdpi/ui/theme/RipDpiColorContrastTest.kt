package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class RipDpiColorContrastTest {
    @Test
    fun `accent content remains readable in both palettes`() {
        assertAaContrast(
            label = "light accent content",
            foreground = LightRipDpiExtendedColors.accentForeground,
            background = LightRipDpiExtendedColors.accent,
        )
        assertAaContrast(
            label = "dark accent content",
            foreground = DarkRipDpiExtendedColors.accentForeground,
            background = DarkRipDpiExtendedColors.accent,
        )
    }

    @Test
    fun `info content remains readable on app surfaces`() {
        listOf(
            "light background" to
                (LightRipDpiExtendedColors.info to LightRipDpiExtendedColors.background),
            "light card" to
                (LightRipDpiExtendedColors.info to LightRipDpiExtendedColors.card),
            "dark background" to
                (DarkRipDpiExtendedColors.info to DarkRipDpiExtendedColors.background),
            "dark card" to
                (DarkRipDpiExtendedColors.info to DarkRipDpiExtendedColors.card),
        ).forEach { (label, colors) ->
            assertAaContrast(label, colors.first, colors.second)
        }
    }

    @Test
    fun `light secondary text remains readable`() {
        val contrast = contrastRatio(LightRipDpiExtendedColors.mutedForeground, LightRipDpiExtendedColors.background)

        assertTrue("Expected AA contrast for light secondary text, was $contrast", contrast >= 4.5f)
    }

    @Test
    fun `dark secondary text remains readable`() {
        val contrast = contrastRatio(DarkRipDpiExtendedColors.mutedForeground, DarkRipDpiExtendedColors.background)

        assertTrue("Expected AA contrast for dark secondary text, was $contrast", contrast >= 4.5f)
    }

    private fun assertAaContrast(
        label: String,
        foreground: Color,
        background: Color,
    ) {
        val contrast = contrastRatio(foreground, background)

        assertTrue("Expected AA contrast for $label, was $contrast", contrast >= 4.5f)
    }

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Float {
        val foregroundLuminance = foreground.relativeLuminance()
        val backgroundLuminance = background.relativeLuminance()
        val lighter = max(foregroundLuminance, backgroundLuminance)
        val darker = min(foregroundLuminance, backgroundLuminance)

        return ((lighter + 0.05f) / (darker + 0.05f))
    }

    private fun Color.relativeLuminance(): Float {
        val red = linearChannel(red)
        val green = linearChannel(green)
        val blue = linearChannel(blue)

        return (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)
    }

    private fun linearChannel(channel: Float): Float =
        if (channel <= 0.04045f) {
            channel / 12.92f
        } else {
            (((channel + 0.055f) / 1.055f).toDouble().pow(2.4)).toFloat()
        }
}
