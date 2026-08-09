package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositeStageCpuTrackerTest {
    @Test
    fun `tracker returns app process cpu consumed during a stage window`() {
        val readings = ArrayDeque(listOf(1_200L, 1_475L))
        val tracker = HomeCompositeStageCpuTracker { readings.removeFirst() }

        tracker.start("session-1")

        assertEquals(275L, tracker.finish("session-1"))
        assertEquals(null, tracker.finish("session-1"))
    }

    @Test
    fun `tracker omits cpu when the platform clock is unavailable`() {
        val tracker = HomeCompositeStageCpuTracker { null }

        tracker.start("session-1")

        assertEquals(null, tracker.finish("session-1"))
    }
}
