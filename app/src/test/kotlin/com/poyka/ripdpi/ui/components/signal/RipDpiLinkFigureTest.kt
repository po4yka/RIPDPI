package com.poyka.ripdpi.ui.components.signal

import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Active
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Complete
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Failed
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Pending
import com.poyka.ripdpi.ui.components.signal.RipDpiLinkStage.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // A failure names its stage only: the plug stops at the fourth contact for the fourth stage.
        val failedAtTunnel = listOf(Pending, Pending, Pending, Failed, Pending)
        assertEquals(6f, figure.target(input(RipDpiLinkMode.Broken, failedAtTunnel)), 0f)
        // A failure that names no stage leaves the plug at the socket, as a connection attempt does.
        assertEquals(15f, figure.target(input(RipDpiLinkMode.Broken, all(Pending))), 0f)
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
        // Four of five lamps are lit in the still frame, each at full strength.
        assertEquals(0.8f, figure.cast, Tolerance)
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
