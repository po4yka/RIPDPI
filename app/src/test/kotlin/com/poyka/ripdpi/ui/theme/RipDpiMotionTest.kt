package com.poyka.ripdpi.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiMotionTest {
    @Test
    fun `semantic tweens use the expected duration buckets`() {
        assertEquals(DefaultRipDpiMotion.quickDurationMillis, DefaultRipDpiMotion.quickTween<Float>().durationMillis)
        assertEquals(DefaultRipDpiMotion.stateDurationMillis, DefaultRipDpiMotion.stateTween<Float>().durationMillis)
        assertEquals(
            DefaultRipDpiMotion.emphasizedDurationMillis,
            DefaultRipDpiMotion.emphasizedTween<Float>().durationMillis,
        )
        assertEquals(DefaultRipDpiMotion.routeDurationMillis, DefaultRipDpiMotion.routeTween<Float>().durationMillis)
    }

    @Test
    fun `reduced motion clamps durations and disables infinite motion`() {
        val reducedMotion = DefaultRipDpiMotion.copy(reducedMotion = true)

        assertEquals(80, reducedMotion.quickTween<Float>().durationMillis)
        assertEquals(110, reducedMotion.stateTween<Float>().durationMillis)
        assertEquals(160, reducedMotion.emphasizedTween<Float>().durationMillis)
        assertEquals(130, reducedMotion.routeTween<Float>().durationMillis)
        assertFalse(reducedMotion.allowsInfiniteMotion)
    }

    @Test
    fun `disabled animations collapse durations and disable infinite motion`() {
        val disabledMotion = DefaultRipDpiMotion.copy(animationsEnabled = false)

        assertEquals(0, disabledMotion.quickTween<Float>().durationMillis)
        assertEquals(0, disabledMotion.stateTween<Float>().durationMillis)
        assertEquals(0, disabledMotion.routeTween<Float>().durationMillis)
        assertFalse(disabledMotion.allowsInfiniteMotion)
    }

    @Test
    fun `motion aware spring removes bounce when reduced motion is active`() {
        val reducedMotion = DefaultRipDpiMotion.copy(reducedMotion = true)
        val spring = reducedMotion.motionAwareSpring<Float>(expressive = true)

        assertEquals(1f, spring.dampingRatio)
        assertEquals(RipDpiMotion.StandardSpringStiffness, spring.stiffness)
    }

    @Test
    fun `semantic tweens preserve default easing categories`() {
        assertSame(RipDpiMotion.StandardEasing, DefaultRipDpiMotion.quickTween<Float>().easing)
        assertSame(RipDpiMotion.StandardEasing, DefaultRipDpiMotion.stateTween<Float>().easing)
        assertSame(RipDpiMotion.EmphasizedDecelerate, DefaultRipDpiMotion.emphasizedTween<Float>().easing)
        assertSame(RipDpiMotion.EmphasizedDecelerate, DefaultRipDpiMotion.routeTween<Float>().easing)
        assertTrue(DefaultRipDpiMotion.allowsInfiniteMotion)
    }
}
