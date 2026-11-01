package com.poyka.ripdpi.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object ContrastAssertions {
    const val Text = 4.5f
    const val NonText = 3f
    const val Enhanced = 7f

    fun ratio(
        foreground: Color,
        background: Color,
    ): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }

    fun assertAtLeast(
        label: String,
        minimum: Float,
        foreground: Color,
        background: Color,
    ) {
        val ratio = ratio(foreground, background)
        assertTrue("Expected at least $minimum:1 for $label, was $ratio", ratio >= minimum)
    }

    private fun Color.luminance(): Float =
        (0.2126f * linear(red)) + (0.7152f * linear(green)) + (0.0722f * linear(blue))

    private fun linear(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else (((channel + 0.055f) / 1.055f).toDouble().pow(2.4)).toFloat()
}
