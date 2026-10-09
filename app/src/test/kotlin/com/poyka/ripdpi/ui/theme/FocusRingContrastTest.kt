package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRingContrastTest {
    @Test
    fun `light button focus rings contrast with their painted surfaces`() = assertButtonContrast(false)

    @Test
    fun `dark button focus rings contrast with their painted surfaces`() = assertButtonContrast(true)

    private fun assertButtonContrast(dark: Boolean) {
        val colors = if (dark) DarkRipDpiExtendedColors else LightRipDpiExtendedColors
        val scheme = if (dark) ripDpiDarkColorScheme() else ripDpiLightColorScheme()
        val tokens = ripDpiStateTokens(colors, scheme, DefaultRipDpiComponents, DefaultRipDpiMotion)
        for (role in RipDpiButtonStateRole.entries) {
            val style = tokens.button.resolve(role, true, false, false, true)
            val surface = if (style.container == Color.Transparent) colors.card else style.container
            assertEquals(2.dp, style.borderWidth)
            assertTrue(
                "dark=$dark role=$role contrast=${contrast(style.border, surface)}",
                contrast(style.border, surface) >= 3f,
            )
        }
    }

    @Test
    fun `light icon focus rings contrast with neutral and selected accent surfaces`() = assertIconContrast(false)

    @Test
    fun `dark icon focus rings contrast with neutral and selected accent surfaces`() = assertIconContrast(true)

    private fun assertIconContrast(dark: Boolean) {
        val colors = if (dark) DarkRipDpiExtendedColors else LightRipDpiExtendedColors
        val scheme = if (dark) ripDpiDarkColorScheme() else ripDpiLightColorScheme()
        val tokens = ripDpiStateTokens(colors, scheme, DefaultRipDpiComponents, DefaultRipDpiMotion)
        for (role in RipDpiIconButtonStateRole.entries) {
            for (selected in listOf(false, true)) {
                val style = tokens.iconButton.resolve(role, true, false, selected, false, true)
                val surface = if (style.container == Color.Transparent) colors.card else style.container
                assertEquals(2.dp, style.borderWidth)
                assertTrue("dark=$dark role=$role selected=$selected", contrast(style.border, surface) >= 3f)
            }
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
