package com.poyka.ripdpi.ui.components.signal

import org.junit.Assert.assertEquals
import org.junit.Test

class RipDpiSignalFieldTest {
    private fun frame(
        amplitude: Float = 0f,
        press: Float = 0f,
    ) = SignalFrame(time = 0.9f, amplitude, pulse = 0f, noise = 0f, cut = 2f, glow = 1f, pressAt = 0.5f, press)

    private fun rippleOnly(
        start: Float,
        end: Float,
    ) = SignalFrame(
        time = 0.9f,
        amplitude = 0f,
        pulse = 0f,
        noise = 0f,
        cut = 2f,
        glow = 0f,
        pressAt = 0.5f,
        press = 0f,
        ripple = SignalRippleFrame(amplitude = 0.12f, cycles = 2f, start = start, end = end),
    )

    @Test
    fun `the hero modes keep their line shape, and only idle is at rest`() {
        // Idle is the one mode at rest: flat as before, and drawn faint with faded ends.
        assertEquals(SignalShape(0f, 0f, 0f, 0f, broken = false, rest = 1f), RipDpiSignalMode.Idle.shape())
        assertEquals(SignalShape(0f, 1f, 0f, 1f, broken = false), RipDpiSignalMode.Seeking.shape())
        assertEquals(SignalShape(0.16f, 0f, 0f, 1f, broken = false), RipDpiSignalMode.Live.shape())
        assertEquals(SignalShape(0.16f, 0f, 0.5f, 1f, broken = false), RipDpiSignalMode.Unstable.shape())
        assertEquals(SignalShape(0.16f, 0f, 0f, 0.5f, broken = true), RipDpiSignalMode.Broken.shape())
    }

    @Test
    fun `an idle line is flat, and dips by the press depth under the finger`() {
        assertEquals(0f, frame().offsetAt(0.3f), 0f)
        assertEquals(0.2f, frame(press = 1f).offsetAt(0.5f), 0f)
    }

    @Test
    fun `a ripple over the whole line has its crests a quarter of a period from the middle`() {
        val wide = rippleOnly(start = 0f, end = 1f)

        assertEquals(0f, wide.offsetAt(0.5f), Tolerance)
        assertEquals(0.12f, wide.offsetAt(0.625f), Tolerance)
        assertEquals(-0.12f, wide.offsetAt(0.375f), Tolerance)
    }

    @Test
    fun `a ripple on one half leaves the other half flat`() {
        val oneSided = rippleOnly(start = 0.5f, end = 1f)

        assertEquals(0f, oneSided.offsetAt(0.2f), 0f)
        assertEquals(0f, oneSided.offsetAt(0.5f), Tolerance)
        // 0.12 * sin(0.8 * pi), where the window is fully open.
        assertEquals(0.070534f, oneSided.offsetAt(0.7f), Tolerance)
        assertEquals(0f, oneSided.offsetAt(1f), Tolerance)
    }

    @Test
    fun `the wave reaches only as far as its front`() {
        val full = frame(amplitude = 0.16f)
        val half = SignalFrame(0.9f, 0.16f, 0f, 0f, cut = 2f, glow = 1f, pressAt = 0.5f, press = 0f, front = 0.5f)

        // Behind the soft edge of the front the wave is whole; past the front the line is flat.
        assertEquals(full.offsetAt(0.3f), half.offsetAt(0.3f), 0f)
        assertEquals(0f, half.offsetAt(0.5f), 0f)
        assertEquals(0f, half.offsetAt(0.7f), 0f)
    }

    @Test
    fun `a line that has just broken recoils at its end and not far from it`() {
        fun broken(kick: Float) =
            SignalFrame(0.9f, 0f, 0f, 0f, cut = 0.5f, glow = 1f, pressAt = 0.5f, press = 0f, kick = kick)

        // At the cut: 0.22 * kick * sin(3 * pi * kick), with kick = 0.5.
        assertEquals(-0.11f, broken(kick = 0.5f).offsetAt(0.5f), Tolerance)
        // One recoil width before the cut the same value is smaller by e.
        assertEquals(-0.11f / Math.E.toFloat(), broken(kick = 0.5f).offsetAt(0.38f), Tolerance)
        assertEquals(0f, broken(kick = 0.5f).offsetAt(0.6f), 0f)
        assertEquals(0f, broken(kick = 0f).offsetAt(0.5f), 0f)
    }

    private companion object {
        const val Tolerance = 1e-5f
    }
}
