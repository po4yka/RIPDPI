package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `dark info and warning containers are muted with readable tinted text`() {
        listOf(
            "dark info container" to
                (DarkRipDpiExtendedColors.infoContainerForeground to DarkRipDpiExtendedColors.infoContainer),
            "dark warning container" to
                (DarkRipDpiExtendedColors.warningContainerForeground to DarkRipDpiExtendedColors.warningContainer),
        ).forEach { (label, colors) ->
            assertAaContrast(label, colors.first, colors.second)
            assertTrue("Expected a low-chroma $label", colors.second.chroma() <= MaxDarkContainerChroma)
            assertTrue("Expected muted text on $label", colors.first.chroma() <= MaxTintedTextChroma)
        }
        assertNotEquals(
            "Warning and info text must differ so a container without an icon still shows its tone",
            DarkRipDpiExtendedColors.warningContainerForeground,
            DarkRipDpiExtendedColors.infoContainerForeground,
        )
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

    @Test
    fun `every Material scheme keeps core text pairs readable at all contrast levels`() {
        RipDpiContrastLevel.entries.forEach { level ->
            val schemes = mapOf("light" to ripDpiLightColorScheme(level), "dark" to ripDpiDarkColorScheme(level))
            schemes.forEach { (mode, s) ->
                assertAaContrast("$mode $level onSurface", s.onSurface, s.surface)
                assertAaContrast("$mode $level onSurfaceVariant", s.onSurfaceVariant, s.surfaceContainerHigh)
                assertAaContrast("$mode $level onPrimary", s.onPrimary, s.primary)
                assertAaContrast("$mode $level onError", s.onError, s.error)
            }
        }
    }

    @Test
    fun `RDS neutral roles come from the selected Material scheme`() {
        val scheme = ripDpiDarkColorScheme(RipDpiContrastLevel.High)
        val colors = ripDpiExtendedColors(scheme, isDark = true)

        assertEquals(scheme.surface, colors.background)
        assertEquals(scheme.surfaceContainerLow, colors.card)
        assertEquals(scheme.onSurfaceVariant, colors.mutedForeground)
        assertEquals(scheme.outlineVariant, colors.border)
        assertEquals(scheme.error, colors.destructive)
        assertEquals(DarkRipDpiExtendedColors.success, colors.success)
    }

    private fun assertAaContrast(
        label: String,
        foreground: Color,
        background: Color,
    ) {
        val contrast = contrastRatio(foreground, background)

        assertTrue("Expected AA contrast for $label, was $contrast", contrast >= 4.5f)
    }

    private fun Color.chroma(): Float = maxOf(red, green, blue) - minOf(red, green, blue)

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

    private companion object {
        // About 7% of the channel range: a tinted grey, not a saturated teal or brown block.
        const val MaxDarkContainerChroma = 0.07f
        const val MaxTintedTextChroma = 0.3f
    }
}
