package com.poyka.ripdpi.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.material3.MotionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiMotionSchemeTest {
    private fun MotionScheme.specs(): List<FiniteAnimationSpec<Float>> =
        listOf(
            fastSpatialSpec(),
            defaultSpatialSpec(),
            slowSpatialSpec(),
            fastEffectsSpec(),
            defaultEffectsSpec(),
            slowEffectsSpec(),
        )

    private fun MotionScheme.durations(): List<Int> = specs().map { (it as TweenSpec<Float>).durationMillis }

    @Test
    fun `default motion maps fast default slow to quick state emphasized`() {
        assertEquals(listOf(120, 220, 320, 120, 220, 320), DefaultRipDpiMotion.toMotionScheme().durations())
    }

    @Test
    fun `reduced motion uses the reduced RDS durations`() {
        val scheme = DefaultRipDpiMotion.copy(reducedMotion = true).toMotionScheme()

        assertEquals(listOf(80, 110, 160, 80, 110, 160), scheme.durations())
    }

    @Test
    fun `disabled animations snap`() {
        val scheme = DefaultRipDpiMotion.copy(animationsEnabled = false, reducedMotion = true).toMotionScheme()

        assertTrue(scheme.specs().all { it is SnapSpec<Float> })
    }
}
