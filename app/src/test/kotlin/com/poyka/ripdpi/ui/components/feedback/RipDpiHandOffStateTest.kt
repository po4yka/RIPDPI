package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `a cut does not run and still counts as a hand-off`() {
        val state = RipDpiHandOffState()

        state.start(animated = false)

        assertFalse(state.isRunning)
        assertEquals(1, state.epoch)
    }

    @Test
    fun `with no destination line the hand-off is the circle at the anchor`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(200f, 300f) }
        state.start()

        assertNull(state.lineBounds(0.5f, screen))
        assertEquals(0f, state.travel(0.5f))
        assertEquals(Offset(200f, 300f), state.ringCenter(0.5f, screen))
    }

    @Test
    fun `the line travels from the source line to the destination line`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f))
        state.start()
        // The source screen leaves the composition in the frame of the swap.
        state.sourceLine = null
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))

        assertEquals(Rect(0f, 272f, 1080f, 328f), state.lineBounds(0f, screen))
        assertEquals(Rect(140f, 586f, 1040f, 642f), state.lineBounds(0.5f, screen))
        assertEquals(Rect(280f, 900f, 1000f, 956f), state.lineBounds(1f, screen))
        assertEquals(Offset(640f, 928f), state.ringCenter(1f, screen))
    }

    @Test
    fun `with no source line the line grows out of the anchor`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.start()
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))

        assertEquals(Rect(540f, 300f, 540f, 300f), state.lineBounds(0f, screen))
        assertEquals(Rect(280f, 900f, 1000f, 956f), state.lineBounds(1f, screen))
    }

    @Test
    fun `a destination that reports late does not make the line jump`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f))
        state.start()
        state.progress = 0.2f
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))

        assertEquals(Rect(0f, 272f, 1080f, 328f), state.lineBounds(0.2f, screen))
        assertEquals(0.5f, state.travel(0.6f), 0.0001f)
        assertEquals(Rect(280f, 900f, 1000f, 956f), state.lineBounds(1f, screen))
    }

    @Test
    fun `a destination line to the left of the anchor is reached in root coordinates`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(900f, 300f) }
        state.start()
        state.targetLine = line(Rect(100f, 900f, 500f, 956f))

        assertEquals(Offset(600f, 614f), state.ringCenter(0.5f, screen))
        assertEquals(Rect(100f, 900f, 500f, 956f), state.lineBounds(1f, screen))
    }

    @Test
    fun `the travelling line takes the look of the destination once it is known`() {
        val state = RipDpiHandOffState()
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f), RipDpiSignalMode.Broken)
        state.start()

        assertEquals(RipDpiSignalMode.Broken, state.line?.mode)

        state.targetLine = line(Rect(280f, 900f, 1000f, 956f), RipDpiSignalMode.Idle)

        assertEquals(RipDpiSignalMode.Idle, state.line?.mode)
    }

    @Test
    fun `a line left over from an earlier visit does not join a later hand-off`() {
        val state = RipDpiHandOffState()
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f))
        state.start()
        state.progress = 1f
        // Onboarding runs again in landscape, where it shows no line.
        state.sourceLine = null

        state.start()

        assertNull(state.lineBounds(0f, screen))
    }

    @Test
    fun `a muted landing stays muted until the next hand-off`() {
        val state = RipDpiHandOffState()
        state.start()
        // The last frame has run and the landing has not yet been given.
        state.progress = 1f

        state.muteLanding()
        assertTrue(state.landingMuted)

        state.start()
        assertFalse(state.landingMuted)
    }

    @Test
    fun `the ring does not jump when the destination reports late`() {
        val state = RipDpiHandOffState().apply { anchorRadius = 80f }
        state.start()
        state.progress = 0.2f
        val before = state.ring(0.2f, opening = 500f, reach = 2500f)

        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))

        assertEquals(500f to 0.8f, before)
        assertEquals(before, state.ring(0.2f, opening = 500f, reach = 2500f))
        assertEquals(0f to 1f, state.ring(1f, opening = 2500f, reach = 2500f))
    }

    private val screen = Size(1080f, 2400f)

    private fun line(
        bounds: Rect,
        mode: RipDpiSignalMode = RipDpiSignalMode.Live,
    ) = RipDpiHandOffLine(bounds, mode, Color.Green)
}
