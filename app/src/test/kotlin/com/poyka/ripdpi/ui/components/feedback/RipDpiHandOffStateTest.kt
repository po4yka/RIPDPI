package com.poyka.ripdpi.ui.components.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiHandOffStateTest {
    @Test
    fun `a new state shows no veil`() {
        assertFalse(RipDpiHandOffState().isRunning)
    }

    @Test
    fun `a second trigger for the same hand-off does not restart the reveal`() {
        val state = RipDpiHandOffState()

        state.start()
        state.progress = 0.4f
        state.start()

        assertTrue(state.isRunning)
        assertEquals(0.4f, state.progress)
        assertEquals(1, state.epoch)
    }

    @Test
    fun `a finished reveal can run again`() {
        val state = RipDpiHandOffState()
        state.start()
        state.progress = 1f

        state.start()

        assertEquals(0f, state.progress)
        assertEquals(2, state.epoch)
    }
}
