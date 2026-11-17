package com.poyka.ripdpi.ui.components.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RipDpiSignalFieldTest {
    private fun rippleOnly(
        start: Float,
        end: Float,
    ) = SignalFrame(ripple = SignalRippleFrame(amplitude = 0.12f, cycles = 2f, start = start, end = end))

    private fun stream() = SignalPacketStream(Random(1))

    /** Runs the stream for [seconds] in display frames and counts the packets that enter the line. */
    private fun SignalPacketStream.sent(
        seconds: Int,
        mode: RipDpiSignalMode,
        traffic: Float,
    ): Int {
        var sent = 0
        var known = packets.toSet()
        repeat(seconds * FramesPerSecond) {
            advance(1f / FramesPerSecond, mode, traffic, origin = 0.5f)
            sent += packets.count { it !in known }
            known = packets.toSet()
        }
        return sent
    }

    @Test
    fun `a line is flat, and dips by the press depth under the finger`() {
        assertEquals(0f, SignalFrame().offsetAt(0.3f), 0f)
        assertEquals(0.2f, SignalFrame(press = 1f).offsetAt(0.5f), 0f)
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
    fun `a line that has just broken recoils at its end and not far from it`() {
        fun broken(kick: Float) = SignalFrame(cut = 0.5f, kick = kick)

        // At the cut: 0.22 * kick * sin(3 * pi * kick), with kick = 0.5.
        assertEquals(-0.11f, broken(kick = 0.5f).offsetAt(0.5f), Tolerance)
        // One recoil width before the cut the same value is smaller by e.
        assertEquals(-0.11f / Math.E.toFloat(), broken(kick = 0.5f).offsetAt(0.38f), Tolerance)
        assertEquals(0f, broken(kick = 0.5f).offsetAt(0.6f), 0f)
        assertEquals(0f, broken(kick = 0f).offsetAt(0.5f), 0f)
    }

    @Test
    fun `a busy line carries more packets than a quiet line, and a silent line only keep-alives`() {
        val silent = stream().sent(seconds = 20, RipDpiSignalMode.Live, traffic = 0f)
        val quiet = stream().sent(seconds = 20, RipDpiSignalMode.Live, traffic = 0.1f)
        val busy = stream().sent(seconds = 20, RipDpiSignalMode.Live, traffic = 1f)

        // One packet at the start, then one in each 2.4 to 5.6 seconds.
        assertTrue("silent sent $silent", silent in 4..9)
        assertTrue("quiet sent $quiet, silent $silent", quiet > silent)
        assertTrue("busy sent $busy, quiet $quiet", busy > quiet * 4)
    }

    @Test
    fun `a line with no path sends nothing and empties by itself`() {
        val stream = stream()
        stream.sent(seconds = 3, RipDpiSignalMode.Live, traffic = 1f)
        assertTrue(stream.packets.isNotEmpty())

        // The slowest packet crosses the line and its own length in under three seconds.
        assertEquals(0, stream.sent(seconds = 3, RipDpiSignalMode.Idle, traffic = 1f))
        assertTrue(stream.packets.isEmpty())
    }

    @Test
    fun `a connection attempt sends one packet at a time, the first from the pointer`() {
        val stream = stream()
        stream.advance(0f, RipDpiSignalMode.Seeking, traffic = 1f, origin = 0.8f)
        assertEquals(listOf(0.8f), stream.packets.map { it.head })

        repeat(9 * FramesPerSecond) {
            stream.advance(1f / FramesPerSecond, RipDpiSignalMode.Seeking, traffic = 1f, origin = 0.8f)
            assertEquals(1, stream.packets.size)
        }
        // The packets after the first enter at the start of the line.
        assertTrue(stream.packets.single().head < 0.8f)
    }

    @Test
    fun `only a line with a warning loses packets`() {
        val live = stream().apply { sent(seconds = 5, RipDpiSignalMode.Live, traffic = 1f) }
        val unstable = stream().apply { sent(seconds = 5, RipDpiSignalMode.Unstable, traffic = 1f) }

        assertTrue(live.packets.all { it.lostAt > 1f })
        assertTrue(unstable.packets.any { it.lostAt < 1f })
    }

    @Test
    fun `a packet shows in the middle of the line and not at its ends, its loss, or past the cut`() {
        assertEquals(1f, SignalPacket(head = 0.5f, length = 0.05f).alpha(cut = 2f), 0f)
        assertEquals(0f, SignalPacket(head = 0f, length = 0.05f).alpha(cut = 2f), 0f)
        assertEquals(0f, SignalPacket(head = 1.05f, length = 0.05f).alpha(cut = 2f), 0f)
        assertEquals(0f, SignalPacket(head = 0.5f, length = 0.05f, lostAt = 0.5f).alpha(cut = 2f), 0f)
        assertEquals(0f, SignalPacket(head = 0.5f, length = 0.05f).alpha(cut = 0.4f), 0f)
    }

    @Test
    fun `the still frame has packets only where a path carries them`() {
        assertTrue(stillPackets(RipDpiSignalMode.Idle).isEmpty())
        assertTrue(stillPackets(RipDpiSignalMode.Broken).isEmpty())
        assertEquals(1, stillPackets(RipDpiSignalMode.Seeking).size)
        assertEquals(4, stillPackets(RipDpiSignalMode.Live).size)
        assertTrue(stillPackets(RipDpiSignalMode.Live).all { it.alpha(cut = 2f) == 1f })
        assertTrue(stillPackets(RipDpiSignalMode.Unstable).any { it.alpha(cut = 2f) < 1f })
    }

    @Test
    fun `the traffic level follows the packet rate on a logarithmic scale`() {
        val meter = SignalTrafficMeter()

        assertEquals(0f, meter.level(totalPackets = 500, atMillis = 1_000), 0f)
        assertEquals(0f, meter.level(totalPackets = 500, atMillis = 2_000), 0f)
        // 9 packets a second: log10(10) / log10(2001).
        assertEquals(0.302916f, meter.level(totalPackets = 509, atMillis = 3_000), Tolerance)
        // 999 packets a second over two seconds: log10(1000) / log10(2001).
        assertEquals(0.908747f, meter.level(totalPackets = 2_507, atMillis = 5_000), Tolerance)
        assertEquals(1f, meter.level(totalPackets = 102_507, atMillis = 6_000), 0f)
        // A new session starts its counter again.
        assertEquals(0f, meter.level(totalPackets = 3, atMillis = 7_000), 0f)
    }

    private companion object {
        const val Tolerance = 1e-5f
        const val FramesPerSecond = 60
    }
}
