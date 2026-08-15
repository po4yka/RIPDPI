package com.poyka.ripdpi.ui.screens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRateEstimatorTest {
    @Test
    fun `no estimate before a step has actually completed`() {
        val estimator = ScanRateEstimator()
        estimator.sample(nowMs = 0L, completedSteps = 0)
        estimator.sample(nowMs = 5_000L, completedSteps = 0)

        assertNull(estimator.remainingMs(nowMs = 5_000L, completedSteps = 0, totalSteps = 20))
    }

    @Test
    fun `projects remaining time from the observed rate`() {
        val estimator = ScanRateEstimator()
        // 4 steps in 4s => 1 step/s, 16 of 20 left => ~16s.
        estimator.sample(nowMs = 0L, completedSteps = 0)
        estimator.sample(nowMs = 4_000L, completedSteps = 4)

        val remaining = estimator.remainingMs(nowMs = 4_000L, completedSteps = 4, totalSteps = 20)

        assertNotNull(remaining)
        assertEquals(16_000L, remaining)
    }

    @Test
    fun `estimate follows a slowdown instead of staying on the whole-run average`() {
        val fast = ScanRateEstimator()
        // Ten quick steps, then one that takes 10s -- the phase cost changed by an order of magnitude.
        fast.sample(nowMs = 0L, completedSteps = 0)
        fast.sample(nowMs = 1_000L, completedSteps = 10)
        val beforeSlowdown = fast.remainingMs(nowMs = 1_000L, completedSteps = 10, totalSteps = 30)!!
        fast.sample(nowMs = 11_000L, completedSteps = 11)
        val afterSlowdown = fast.remainingMs(nowMs = 11_000L, completedSteps = 11, totalSteps = 30)!!

        assertTrue(
            "a slower phase must raise the estimate, was $beforeSlowdown then $afterSlowdown",
            afterSlowdown > beforeSlowdown,
        )
    }

    @Test
    fun `countdown keeps falling while a single slow step is still running`() {
        val estimator = ScanRateEstimator()
        estimator.sample(nowMs = 0L, completedSteps = 0)
        estimator.sample(nowMs = 2_000L, completedSteps = 2)

        val atStepEnd = estimator.remainingMs(nowMs = 2_000L, completedSteps = 2, totalSteps = 10)!!
        val threeSecondsLater = estimator.remainingMs(nowMs = 5_000L, completedSteps = 2, totalSteps = 10)!!

        assertEquals(3_000L, atStepEnd - threeSecondsLater)
    }

    @Test
    fun `a stalled step never drives the estimate below zero`() {
        val estimator = ScanRateEstimator()
        estimator.sample(nowMs = 0L, completedSteps = 0)
        estimator.sample(nowMs = 1_000L, completedSteps = 1)

        val remaining = estimator.remainingMs(nowMs = 10_000_000L, completedSteps = 1, totalSteps = 10)

        assertEquals(0L, remaining)
    }

    @Test
    fun `no estimate once every step is done`() {
        val estimator = ScanRateEstimator()
        estimator.sample(nowMs = 0L, completedSteps = 0)
        estimator.sample(nowMs = 1_000L, completedSteps = 10)

        assertNull(estimator.remainingMs(nowMs = 1_000L, completedSteps = 10, totalSteps = 10))
    }

    @Test
    fun `no estimate when the plan reports no steps`() {
        val estimator = ScanRateEstimator()
        estimator.sample(nowMs = 0L, completedSteps = 0)
        estimator.sample(nowMs = 1_000L, completedSteps = 1)

        assertNull(estimator.remainingMs(nowMs = 1_000L, completedSteps = 1, totalSteps = 0))
    }
}
