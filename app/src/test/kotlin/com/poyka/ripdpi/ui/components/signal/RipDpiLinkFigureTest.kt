package com.poyka.ripdpi.ui.components.signal

import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Active
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Complete
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Failed
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Pending
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RipDpiLinkFigureTest {
    private fun all(stage: RipDpiLinkStage) = List(LinkStageCount) { stage }

    private fun input(
        mode: RipDpiLinkMode,
        stages: List<RipDpiLinkStage> = all(if (mode == RipDpiLinkMode.Open) Pending else Complete),
        sent: Float = 0.5f,
        received: Float = 0.5f,
        press: Float = 0f,
    ) = LinkInput(mode, stages, sent, received, press)

    private fun figure(input: LinkInput) = LinkFigure(Random(1)).apply { settle(input) }

    /** Runs the figure for [seconds] in display frames and calls [each] after every frame. */
    private fun LinkFigure.run(
        seconds: Float,
        input: LinkInput,
        each: () -> Unit = {},
    ) {
        repeat((seconds * FramesPerSecond).toInt()) {
            advance(1f / FramesPerSecond, input)
            each()
        }
    }

    @Test
    fun `the plug rests loose, at the latch, one contact in for each stage, or home`() {
        val figure = LinkFigure()

        assertEquals(34f, figure.target(input(RipDpiLinkMode.Open)), 0f)
        assertEquals(29f, figure.target(input(RipDpiLinkMode.Open, press = 1f)), 0f)
        assertEquals(18f, figure.target(input(RipDpiLinkMode.Held)), 0f)
        assertEquals(0f, figure.target(input(RipDpiLinkMode.Live)), 0f)
        // Two of five stages are complete: three contacts of 3 dp are left.
        val two = listOf(Complete, Complete, Active, Pending, Pending)
        assertEquals(9f, figure.target(input(RipDpiLinkMode.Seeking, two)), 0f)
        // A failed plug stops at the socket, whichever stage failed.
        val failedAtTunnel = listOf(Pending, Pending, Pending, Failed, Pending)
        assertEquals(15f, figure.target(input(RipDpiLinkMode.Broken, failedAtTunnel)), 0f)
        assertEquals(15f, figure.target(input(RipDpiLinkMode.Seeking, all(Active))), 0f)
    }

    @Test
    fun `a finger holds the plug where it is`() {
        val held = LinkInput(RipDpiLinkMode.Open, all(Pending), drag = 12f)
        val figure = figure(input(RipDpiLinkMode.Open))

        figure.run(0.1f, held)

        assertEquals(12f, figure.pull.at, 0f)
        assertTrue(figure.moving(held))
    }

    @Test
    fun `the plug seats with a small overshoot and a ring spreads from the contact`() {
        val figure = figure(input(RipDpiLinkMode.Open))
        var deepest = Float.MAX_VALUE
        var ringRestarted = false

        figure.run(1.5f, input(RipDpiLinkMode.Live)) {
            deepest = minOf(deepest, figure.pull.at)
            if (figure.ring < 0.5f) ringRestarted = true
        }

        assertTrue("deepest $deepest", deepest < 0f && deepest > -2.5f)
        assertEquals(0f, figure.pull.at, 0.05f)
        assertTrue(ringRestarted)
        assertEquals(1f, figure.ring, 0f)
        // A seated plug is in line with the socket.
        assertEquals(0f, figure.loose, 0f)
    }

    @Test
    fun `the plug comes out without an overshoot and lies loose`() {
        val figure = figure(input(RipDpiLinkMode.Live))
        var farthest = 0f

        figure.run(1.5f, input(RipDpiLinkMode.Open)) { farthest = maxOf(farthest, figure.pull.at) }

        assertTrue("farthest $farthest", farthest <= 34.01f)
        assertEquals(34f, figure.pull.at, 0.05f)
        assertEquals(1f, figure.loose, 0.01f)
    }

    @Test
    fun `a stage that completes makes the socket recoil`() {
        val one = listOf(Complete, Active, Pending, Pending, Pending)
        val two = listOf(Complete, Complete, Active, Pending, Pending)
        val figure = figure(input(RipDpiLinkMode.Seeking, one))
        figure.run(1f, input(RipDpiLinkMode.Seeking, one))
        assertEquals(0f, figure.recoil.at, 0.02f)

        // The contact sets the socket moving in one frame, and the next frame moves it.
        figure.run(2f / FramesPerSecond, input(RipDpiLinkMode.Seeking, two))

        assertTrue("recoil ${figure.recoil.at}", figure.recoil.at < -0.1f)
    }

    @Test
    fun `a busy lane carries more packets than a quiet lane, and each lane follows its own traffic`() {
        fun count(
            sent: Float,
            received: Float,
        ): Pair<Int, Int> {
            val live = input(RipDpiLinkMode.Live, sent = sent, received = received)
            val figure = figure(input(RipDpiLinkMode.Open))
            figure.run(10f, live)
            return figure.sent.pulses.size to figure.received.pulses.size
        }

        val (busySent, quietReceived) = count(sent = 1f, received = 0.1f)
        val (silentSent, _) = count(sent = 0f, received = 1f)

        assertTrue("busy $busySent, quiet $quietReceived", busySent > quietReceived * 2)
        // With no traffic one keep-alive packet is on the cable at most: it crosses in less than its interval.
        assertTrue("silent $silentSent", silentSent <= 1)
    }

    @Test
    fun `a connection attempt sends one probe at a time from the plug`() {
        // The app reports no stage progress during an attempt: the plug waits at the socket.
        val seeking = input(RipDpiLinkMode.Seeking, all(Active))
        val figure = figure(seeking)

        figure.advance(1f / FramesPerSecond, seeking)
        assertEquals(1, figure.sent.pulses.size)
        assertEquals(
            LinkDefaultProbeFrom,
            figure.sent.pulses
                .single()
                .at,
            0f,
        )

        figure.run(6f, seeking) { assertEquals(1, figure.sent.pulses.size) }
        assertTrue(figure.received.pulses.isEmpty())
    }

    @Test
    fun `a failure stops the packets and shakes the plug once`() {
        val figure = figure(input(RipDpiLinkMode.Open))
        figure.run(4f, input(RipDpiLinkMode.Live, sent = 1f, received = 1f))
        assertTrue(figure.sent.pulses.isNotEmpty())
        val failed = input(RipDpiLinkMode.Broken, listOf(Pending, Pending, Pending, Failed, Pending))

        figure.advance(1f / FramesPerSecond, failed)
        assertTrue(figure.shake < 0.1f)
        figure.run(2f, failed)

        assertTrue(figure.sent.pulses.isEmpty() && figure.received.pulses.isEmpty())
        assertEquals(1f, figure.shake, 0f)
        assertEquals(0f, figure.shakeBy, 0f)
        assertFalse(figure.moving(failed))
    }

    @Test
    fun `the still frame of a live link has packets at their spacing, and no other state has any`() {
        // At full traffic the gap is 0.16 seconds at 110 dp a second, after a packet of 9 dp.
        val live = figure(input(RipDpiLinkMode.Live, sent = 1f, received = 0f))
        val gaps = live.sent.pulses.zipWithNext { a, b -> b.at - a.at }

        assertTrue(gaps.isNotEmpty())
        gaps.forEach { assertEquals(26.6f, it, Tolerance) }
        // No received traffic: one keep-alive packet in each 4 seconds, which is longer than the cable.
        assertEquals(1, live.received.pulses.size)
        assertTrue(figure(input(RipDpiLinkMode.Open)).sent.pulses.isEmpty())
        assertTrue(figure(input(RipDpiLinkMode.Held)).sent.pulses.isEmpty())
    }

    @Test
    fun `the still frame shows traffic in steps, so a small change does not shift it`() {
        fun count(sent: Float) = figure(input(RipDpiLinkMode.Live, sent = sent)).sent.pulses.size

        assertEquals(count(0.30f), count(0.45f))
        assertTrue(count(0.9f) > count(0.3f))
    }

    @Test
    fun `a lamp is round, a warning gives it points, and a failure turns them`() {
        val stages = listOf(Complete, Warning, Failed, Active, Pending)
        val figure = figure(input(RipDpiLinkMode.Broken, stages))

        assertEquals(listOf(0f, 0.24f, 0.34f, 0f, 0f), figure.lamps.map { it.notch })
        assertEquals(Math.PI.toFloat(), figure.lamps[2].turn, 0f)
        assertEquals(0f, figure.lamps[1].turn, 0f)
    }

    @Test
    fun `lamps come on from the device end and go off from the network end`() {
        val figure = figure(input(RipDpiLinkMode.Open))
        val live = input(RipDpiLinkMode.Live)
        val order = ArrayList<Int>()

        fun record(lit: Boolean) =
            figure.lamps.forEachIndexed { index, lamp ->
                if ((lamp.stage == Complete) == lit && index !in order) order += index
            }
        figure.run(1f, live) { record(lit = true) }
        assertEquals(listOf(0, 1, 2, 3, 4), order)
        // A lamp that comes on swells for a moment, and is its own size again at rest.
        figure.lamps.forEach { assertEquals(1f, it.size.at, 0.02f) }

        order.clear()
        figure.run(1f, input(RipDpiLinkMode.Open)) { record(lit = false) }
        assertEquals(listOf(4, 3, 2, 1, 0), order)
    }

    @Test
    fun `a live link lights the cable after the plug seats, and the light goes when the session ends`() {
        val figure = figure(input(RipDpiLinkMode.Open))
        val live = input(RipDpiLinkMode.Live)

        figure.advance(1f / FramesPerSecond, live)
        assertEquals(0f, figure.charge, 0f)
        figure.run(2f, live)
        assertEquals(1f, figure.charge, 0f)

        figure.run(1f, input(RipDpiLinkMode.Open))
        assertEquals(0f, figure.charge, 0f)
    }

    @Test
    fun `a plug that falls out bounces once, and a plug on its way in is in line with the socket`() {
        val figure = figure(input(RipDpiLinkMode.Live))
        var deepest = 0f

        figure.run(2f, input(RipDpiLinkMode.Open)) { deepest = maxOf(deepest, figure.lie.at) }
        assertTrue("deepest $deepest", deepest > 1.02f && deepest < 1.4f)
        assertEquals(1f, figure.lie.at, 0.02f)

        figure.run(2f, input(RipDpiLinkMode.Live)) { assertEquals(figure.loose, figure.lie.at, 0f) }
    }

    @Test
    fun `the cable hangs lower behind a loose plug, swings when the plug comes out, and comes to rest`() {
        fun lowest(figure: LinkFigure) = (0..10).maxOf { figure.rope.y(it / 10f) }
        val seated = lowest(figure(input(RipDpiLinkMode.Live)))
        val loose = lowest(figure(input(RipDpiLinkMode.Open)))
        assertTrue("seated $seated, loose $loose", loose > seated + 3f)

        val open = input(RipDpiLinkMode.Open)
        val figure = figure(input(RipDpiLinkMode.Live))
        var deepest = 0f
        figure.run(4f, open) { deepest = maxOf(deepest, lowest(figure)) }

        // The swing takes the cable below where it rests, and the tile then takes no more frames.
        assertTrue("deepest $deepest, rest ${lowest(figure)}", deepest > lowest(figure) + 0.5f)
        assertEquals(loose, lowest(figure), 0.5f)
        assertFalse(figure.moving(open))
    }

    @Test
    fun `a faster plug hits the socket harder, and a plug that a finger lets go keeps its speed`() {
        fun hit(from: RipDpiLinkMode): Float {
            val figure = figure(input(from, all(Active)))
            var hardest = 0f
            figure.run(1f, input(RipDpiLinkMode.Live)) { hardest = minOf(hardest, figure.recoil.at) }
            return -hardest
        }
        // From loose the plug has farther to go than from the socket, so it arrives faster.
        assertTrue(hit(RipDpiLinkMode.Open) > hit(RipDpiLinkMode.Seeking) * 1.2f)

        val figure = figure(input(RipDpiLinkMode.Open))
        figure.advance(1f / FramesPerSecond, LinkInput(RipDpiLinkMode.Open, all(Pending), drag = 32f))
        assertTrue("rate ${figure.pull.rate}", figure.pull.rate < -100f)
    }

    @Test
    fun `a busy lane sends longer packets, some of them close together`() {
        val figure = figure(input(RipDpiLinkMode.Open))
        val lengths = HashSet<Float>()
        var closest = Float.MAX_VALUE

        figure.run(10f, input(RipDpiLinkMode.Live, sent = 1f, received = 0f)) {
            figure.sent.pulses.forEach { lengths += it.length }
            figure.sent.pulses.zipWithNext { ahead, behind ->
                closest = minOf(closest, ahead.at - ahead.length - behind.at)
            }
        }

        assertTrue(lengths.max() > 11f && lengths.min() < 8f)
        // The packets of a burst are 3 dp apart, and one frame of travel more at most.
        assertTrue("closest $closest", closest >= 3f && closest < 5f)
        // With no traffic every packet is short.
        figure.received.pulses.forEach { assertEquals(5f, it.length, 0f) }
    }

    @Test
    fun `with no live session a packet does not cross the connector`() {
        val figure = figure(input(RipDpiLinkMode.Open))
        figure.run(4f, input(RipDpiLinkMode.Live, sent = 1f, received = 1f))
        val open = input(RipDpiLinkMode.Open)

        // Past the plug for a sent packet, and past the socket for a received packet.
        fun crossed() =
            figure.sent.pulses.count { it.at >= LinkDefaultProbeFrom } to
                figure.received.pulses.count { it.at >= LinkDefaultLength - LinkDefaultProbeFrom + 114f }
        // A packet that is at the end of the connector when the session ends is through it one frame later.
        figure.advance(1f / FramesPerSecond, open)
        var before = crossed()
        assertTrue(before.first > 0 && before.second > 0)

        figure.run(5f, open) {
            val now = crossed()
            assertTrue(now.first <= before.first && now.second <= before.second)
            before = now
        }
        assertTrue(figure.sent.pulses.isEmpty() && figure.received.pulses.isEmpty())
    }

    @Test
    fun `a cable that has moved comes back to its still frame`() {
        val live = input(RipDpiLinkMode.Live)
        val figure = figure(live)

        fun shape() = (0..10).map { figure.rope.y(it / 10f) }
        val still = shape()

        // The plug comes out and the cable swings. The still frame of the same state is then the first one again.
        figure.run(0.3f, input(RipDpiLinkMode.Open))
        assertNotEquals(still, shape())
        figure.settle(live)
        assertEquals(still, shape())
    }

    @Test
    fun `a figure is steady only while its plug, socket, and cable are at rest`() {
        val live = input(RipDpiLinkMode.Live, sent = 1f, received = 1f)
        val figure = figure(input(RipDpiLinkMode.Open))

        // The plug is on its way in.
        figure.advance(1f / FramesPerSecond, live)
        assertFalse(figure.steady(live))

        // The plug is seated and the cable has come to rest. Packets still travel.
        figure.run(4f, live)
        assertFalse(figure.sent.pulses.isEmpty())
        assertTrue(figure.steady(live))
        // A finger on the plug can move it in any frame.
        assertFalse(figure.steady(LinkInput(RipDpiLinkMode.Live, all(Complete), drag = 0f)))
    }

    @Test
    fun `a live link with an empty cable rests until its next packet, for one second at most`() {
        val silent = input(RipDpiLinkMode.Live, sent = 0f, received = 0f)
        val figure = figure(input(RipDpiLinkMode.Open))

        fun empty() = figure.sent.pulses.isEmpty() && figure.received.pulses.isEmpty()
        // The plug seats and the first keep-alive packets cross the cable.
        figure.run(1f, silent)
        assertFalse(empty())
        assertEquals(0f, figure.quiet(silent), 0f)

        var frames = 0
        while (!empty() && frames++ < 20 * FramesPerSecond) figure.advance(1f / FramesPerSecond, silent)
        assertTrue(empty())
        val rest = figure.quiet(silent)
        assertTrue("rest $rest", rest > 0f && rest <= 1f)

        // After the rests, the next keep-alive packet comes, and the link takes frames again.
        frames = 0
        while (empty() && frames++ < 10 * FramesPerSecond) {
            val quiet = figure.quiet(silent)
            if (quiet > 0f) figure.skip(quiet) else figure.advance(1f / FramesPerSecond, silent)
        }
        assertFalse(empty())
        assertEquals(0f, figure.quiet(silent), 0f)
        // A warning lamp breathes, so a link with a warning does not rest.
        assertEquals(0f, figure(silent).quiet(input(RipDpiLinkMode.Unstable, sent = 0f, received = 0f)), 0f)
    }

    @Test
    fun `a settled tile with no session takes no frames, and a live one does`() {
        val open = input(RipDpiLinkMode.Open)
        val live = input(RipDpiLinkMode.Live)

        assertFalse(figure(open).moving(open))
        assertTrue(figure(live).moving(live))
        // A plug that has reached its lean still takes frames while the action is pressed, so a release moves it.
        val pressed = input(RipDpiLinkMode.Open, press = 1f)
        assertTrue(figure(pressed).moving(pressed))
    }

    private companion object {
        const val Tolerance = 1e-3f
        const val FramesPerSecond = 60
    }
}
