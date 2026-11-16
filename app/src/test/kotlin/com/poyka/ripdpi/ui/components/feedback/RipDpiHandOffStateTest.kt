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
import kotlin.math.hypot

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
        val state =
            RipDpiHandOffState().apply {
                anchor = Offset(540f, 300f)
                anchorRadius = 80f
            }
        state.start()
        state.settle()

        val frame = state.frame(0.5f, screen)

        assertFalse(state.travels)
        assertNull(frame.line)
        assertEquals(Offset(540f, 300f), frame.openingCenter)
        // Half of the distance from the anchor to the farthest corner, (540, 2100) away.
        assertEquals(hypot(540f, 2100f) / 2f, frame.openingRadius, 0.01f)
        assertEquals(frame.openingRadius, frame.ringRadius)
        assertEquals(Offset(540f, 300f), frame.ringCenter)
        assertEquals(0.5f, frame.ringAlpha)
    }

    @Test
    fun `the line travels from the source line to the destination line over the closed veil`() {
        val state =
            RipDpiHandOffState().apply {
                anchor = Offset(540f, 300f)
                anchorRadius = 80f
            }
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f))
        state.start()
        // The source screen leaves the composition in the frame of the swap.
        state.sourceLine = null
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))
        state.settle()

        val start = state.frame(0f, screen)
        assertEquals(Rect(0f, 272f, 1080f, 328f), start.line)
        assertEquals(80f, start.ringRadius)
        assertEquals(0f, start.openingRadius)

        // Half of the travel, which takes the first 0.6 of the reveal.
        val halfWay = state.frame(0.3f, screen)
        assertEquals(Rect(140f, 586f, 1040f, 642f), halfWay.line)
        assertEquals(Offset(590f, 614f), halfWay.ringCenter)
        assertEquals(40f, halfWay.ringRadius)
        assertEquals(1f, halfWay.lineAlpha)
        assertEquals(0f, halfWay.openingRadius)

        val landed = state.frame(0.6f, screen)
        assertEquals(Rect(280f, 900f, 1000f, 956f), landed.line)
        assertEquals(0f, landed.ringRadius)
        assertEquals(0f, landed.openingRadius)
    }

    @Test
    fun `the destination opens from the landed line`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.start()
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))
        state.settle()

        val frame = state.frame(0.8f, screen)

        assertEquals(Offset(640f, 928f), frame.openingCenter)
        // Half of the distance from the line center to the farthest corner, (640, 1472) away.
        assertEquals(hypot(640f, 1472f) / 2f, frame.openingRadius, 0.01f)
        assertEquals(Rect(280f, 900f, 1000f, 956f), frame.line)
    }

    @Test
    fun `with no source line the line grows out of the anchor and fades in`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.start()
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))
        state.settle()

        assertEquals(Rect(540f, 300f, 540f, 300f), state.frame(0f, screen).line)
        assertEquals(0f, state.frame(0f, screen).lineAlpha)
        assertEquals(0.5f, state.frame(0.06f, screen).lineAlpha, 0.0001f)
        assertEquals(1f, state.frame(0.3f, screen).lineAlpha)
    }

    @Test
    fun `a destination line reported after the reveal is on its way does not change it`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.start()
        state.settle()
        val before = state.frame(0.4f, screen)

        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))

        assertFalse(state.travels)
        assertEquals(before, state.frame(0.4f, screen))
    }

    @Test
    fun `a destination line that leaves on the way turns the reveal into the circle`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(540f, 300f) }
        state.start()
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))
        state.settle()

        state.targetLine = null

        assertNull(state.frame(0.3f, screen).line)
        assertEquals(Offset(540f, 300f), state.frame(0.3f, screen).openingCenter)
    }

    @Test
    fun `a destination line to the left of the anchor is reached in root coordinates`() {
        val state = RipDpiHandOffState().apply { anchor = Offset(900f, 300f) }
        state.start()
        state.targetLine = line(Rect(100f, 900f, 500f, 956f))
        state.settle()

        assertEquals(Offset(600f, 614f), state.frame(0.3f, screen).ringCenter)
        assertEquals(Rect(100f, 900f, 500f, 956f), state.frame(0.6f, screen).line)
    }

    @Test
    fun `the line takes the look of the destination when it travels`() {
        val state = RipDpiHandOffState()
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f), RipDpiSignalMode.Broken)
        state.start()
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f), RipDpiSignalMode.Idle)

        assertEquals(RipDpiSignalMode.Broken, state.line?.mode)

        state.settle()

        assertEquals(RipDpiSignalMode.Idle, state.line?.mode)
    }

    @Test
    fun `a finished reveal no longer hides the destination line`() {
        val state = RipDpiHandOffState()
        state.start()
        state.targetLine = line(Rect(280f, 900f, 1000f, 956f))
        state.settle()
        assertTrue(state.travels)

        state.finish()

        assertFalse(state.travels)
        assertFalse(state.isRunning)
    }

    @Test
    fun `a line left over from an earlier visit does not join a later hand-off`() {
        val state = RipDpiHandOffState()
        state.sourceLine = line(Rect(0f, 272f, 1080f, 328f))
        state.start()
        state.finish()
        // Onboarding runs again in landscape, where it shows no line.
        state.sourceLine = null

        state.start()

        assertNull(state.frame(0f, screen).line)
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

    private val screen = Size(1080f, 2400f)

    private fun line(
        bounds: Rect,
        mode: RipDpiSignalMode = RipDpiSignalMode.Live,
    ) = RipDpiHandOffLine(bounds, mode, Color.Green)
}
