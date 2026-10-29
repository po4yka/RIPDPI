package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiTypographyTest {
    private val typography = RipDpiTypography

    private val baseline: Map<String, TextStyle> =
        mapOf(
            "displayLarge" to typography.displayLarge,
            "displayMedium" to typography.displayMedium,
            "displaySmall" to typography.displaySmall,
            "headlineLarge" to typography.headlineLarge,
            "headlineMedium" to typography.headlineMedium,
            "headlineSmall" to typography.headlineSmall,
            "titleLarge" to typography.titleLarge,
            "titleMedium" to typography.titleMedium,
            "titleSmall" to typography.titleSmall,
            "bodyLarge" to typography.bodyLarge,
            "bodyMedium" to typography.bodyMedium,
            "bodySmall" to typography.bodySmall,
            "labelLarge" to typography.labelLarge,
            "labelMedium" to typography.labelMedium,
            "labelSmall" to typography.labelSmall,
        )

    private val emphasized: Map<String, TextStyle> =
        mapOf(
            "displayLarge" to typography.displayLargeEmphasized,
            "displayMedium" to typography.displayMediumEmphasized,
            "displaySmall" to typography.displaySmallEmphasized,
            "headlineLarge" to typography.headlineLargeEmphasized,
            "headlineMedium" to typography.headlineMediumEmphasized,
            "headlineSmall" to typography.headlineSmallEmphasized,
            "titleLarge" to typography.titleLargeEmphasized,
            "titleMedium" to typography.titleMediumEmphasized,
            "titleSmall" to typography.titleSmallEmphasized,
            "bodyLarge" to typography.bodyLargeEmphasized,
            "bodyMedium" to typography.bodyMediumEmphasized,
            "bodySmall" to typography.bodySmallEmphasized,
            "labelLarge" to typography.labelLargeEmphasized,
            "labelMedium" to typography.labelMediumEmphasized,
            "labelSmall" to typography.labelSmallEmphasized,
        )

    @Test
    fun `all thirty styles use Geist Sans and sp units`() {
        (baseline + emphasized.mapKeys { "${it.key}Emphasized" }).forEach { (name, style) ->
            assertEquals(name, GeistSansFamily, style.fontFamily)
            assertTrue("$name fontSize", style.fontSize.isSp)
            assertTrue("$name lineHeight", style.lineHeight.isSp)
        }
    }

    @Test
    fun `emphasized styles keep the baseline size and raise the weight`() {
        baseline.forEach { (name, style) ->
            val strong = emphasized.getValue(name)
            assertEquals(name, style.fontSize, strong.fontSize)
            assertEquals(name, style.lineHeight, strong.lineHeight)
            assertTrue(name, strong.fontWeight!!.weight > style.fontWeight!!.weight)
            assertTrue(name, strong.fontWeight!! <= FontWeight.Bold)
        }
    }
}
