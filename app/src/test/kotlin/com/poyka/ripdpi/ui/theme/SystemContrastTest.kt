package com.poyka.ripdpi.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemContrastTest {
    @Test
    fun `system contrast maps to levels at the documented thresholds`() {
        val expected =
            listOf(
                -1f to RipDpiContrastLevel.Standard,
                0f to RipDpiContrastLevel.Standard,
                0.249f to RipDpiContrastLevel.Standard,
                0.25f to RipDpiContrastLevel.Medium,
                0.5f to RipDpiContrastLevel.Medium,
                0.749f to RipDpiContrastLevel.Medium,
                0.75f to RipDpiContrastLevel.High,
                1f to RipDpiContrastLevel.High,
            )
        expected.forEach { (value, level) -> assertEquals("contrast $value", level, contrastLevelFor(value)) }
    }
}
