// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val Pi = PI.toFloat()
private const val Home = 50f
private const val PostTop = Ground - 34f
private const val LampY = Ground - 40f
private const val Checks = 3
private const val FoundAt = 5.1f
private val PostX = floatArrayOf(78f, 126f, 174f, 222f)
private val CheckAt = floatArrayOf(1.9f, 3.5f, FoundAt)

/**
 * Trouble. The line from the device to the open internet hangs broken between two posts, and the device shows it.
 * The ghost takes a lens along the line. The lamp of each post that still carries the signal turns to the signal
 * color; the lamp at the break turns to the danger color. The ghost writes the place on a sheet and sends it off.
 */
internal class TroubleScene(
    parts: Parts,
) : Scene(parts) {
    private val loop = Loop(10f)
    private val lensX = Spring(Home)
    private val lamps = List(Checks) { Spring() }

    override val stillAt = 6f

    override fun enter(frame: Frame) {
        super.enter(frame)
        loop.reset()
        lensX.x = Home
        lensX.v = 0f
    }

    override fun Pen.draw(frame: Frame) {
        val dt = frame.dt
        loop.step(since)
        val p = loop.at
        val target =
            when {
                p < 0.6f || p >= 9f -> Home
                p < 2.2f -> PostX[0]
                p < 3.8f -> PostX[1]
                else -> PostX[2]
            }
        lensX.step(target, 38f, 9f, dt)
        lamps.forEachIndexed { i, lamp ->
            lamp.step(if (p >= CheckAt[i] && p < 9.2f) 1f else 0f, 140f, 14f, dt)
            if (loop.passed(CheckAt[i])) {
                parts.ring(PostX[i], LampY, if (i == Checks - 1) colors.danger else colors.signal, 3f)
            }
        }
        if (loop.passed(2.6f) || loop.passed(7.4f)) parts.spark(PostX[2] + 14f, PostTop + 20f, colors.danger, 3, 30f)
        sky(0.7f)
        rise(0f) {
            shadow(30f, Ground + 1f, 14f)
            phone(30f, Ground, 24f, 44f, -0.04f, colors.night)
            circ(30f, Ground - 22f, 3f + sin(time * 4f) * 0.6f)
            fill(colors.danger)
            circ(30f, Ground - 22f, 7f)
            stroke(colors.danger, 0.4f, 1f)
        }
        rise(5f) { globe(270f, Ground - 28f, 12f, 0f, 0.2f) }
        line()
        repeat(PostX.size) { i -> rise(1f + i) { post(i) } }
        inspector(p, target)
        report(p)
    }

    private fun Pen.span(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) = shape {
        moveTo(x0, y0)
        quadraticTo((x0 + x1) / 2f, (y0 + y1) / 2f + 7f, x1, y1)
    }

    /** The signal runs along the line as far as the break. Past the break the line is dead. */
    private fun Pen.line() {
        val shown = inn(3f)
        for (i in -1..1) {
            if (i < 0) span(40f, Ground - 38f, PostX[0], PostTop) else span(PostX[i], PostTop, PostX[i + 1], PostTop)
            stroke(colors.ink, 0.7f * shown, 1.3f)
            stroke(colors.signal, 0.8f * shown, 1.3f, dash = 2f, gap = 5f, phase = -time * 14f)
        }
        val sway = sin(time * 2.1f) * 2.5f
        shape {
            moveTo(PostX[2], PostTop)
            quadraticTo(PostX[2] + 10f, PostTop + 2f, PostX[2] + 14f + sway, PostTop + 20f)
        }
        stroke(colors.ink, 0.7f * shown, 1.3f)
        shape {
            moveTo(PostX[3], PostTop)
            quadraticTo(PostX[3] - 9f, PostTop + 2f, PostX[3] - 12f - sway * 0.6f, PostTop + 17f)
        }
        stroke(colors.ink, 0.35f * shown, 1.3f)
        span(PostX[3], PostTop, 259f, Ground - 32f)
        stroke(colors.ink, 0.35f * shown, 1.3f)
    }

    private fun Pen.post(index: Int) {
        val x = PostX[index]
        seg(x, Ground, x, PostTop, colors.ink, 0.9f, 2.2f)
        rr(x - 5f, Ground - 3f, 10f, 3f, 1.5f)
        fill(colors.ink, 0.9f)
        seg(x - 4f, PostTop, x + 4f, PostTop, colors.ink, 0.9f, 1.6f)
        // The lamp is larger under the lens.
        val r = 4f * (1f + cl(1f - abs(lensX.x - x) / 9f) * 0.35f)
        circ(x, LampY, r)
        fill(colors.paper)
        fill(colors.ink, 0.2f)
        stroke(colors.ink, 0.8f, 1.2f)
        if (index >= Checks) return
        val k = cl(lamps[index].x)
        val broken = index == Checks - 1
        val color = if (broken) colors.danger else colors.signal
        circ(x, LampY, r + 3.5f + if (broken) sin(time * 6f) * 1.2f else 0f)
        fill(color, 0.18f * k)
        circ(x, LampY, r)
        fill(color, k)
        if (broken) {
            seg(x - 1.6f, LampY - 1.6f, x + 1.6f, LampY + 1.6f, colors.paper, k, 1.1f)
            seg(x - 1.6f, LampY + 1.6f, x + 1.6f, LampY - 1.6f, colors.paper, k, 1.1f)
        } else {
            seg(x - 2f, LampY, x - 0.6f, LampY + 1.6f, colors.paper, k, 1.1f)
            seg(x - 0.6f, LampY + 1.6f, x + 2.2f, LampY - 1.6f, colors.paper, k, 1.1f)
        }
    }

    /** The ghost holds the lens over a lamp, and floats above and beside it. */
    private fun Pen.inspector(
        p: Float,
        target: Float,
    ) {
        val x = lensX.x
        val found = p >= FoundAt && p < 9f
        circ(x, LampY, 8.5f)
        fill(colors.paper, 0.3f)
        stroke(colors.ink, 0.9f, 1.8f)
        seg(x + 6f, LampY - 6f, x + 11f, LampY - 12f, colors.ink, 0.9f, 2.4f)
        ghost(
            Ghost(
                x = x + 14f,
                y = Ground - 54f,
                lag = lensX.v / 60f,
                look = -1f,
                lookY = 1f,
                eye = if (!found && target != Home && abs(x - target) < 6f) Eye.Narrow else Eye.Open,
                id = 7,
            ),
        )
        val u = (p - FoundAt) / 1.7f
        if (u > 0f && u < 1f) {
            text("!", x + 14f, 46f - u * 5f, 13f, colors.danger, cl(u * 6f) * cl((1f - u) * 3f), TextCarved)
        }
    }

    /** The sheet with the place of the break. It shows beside the ghost, and then it leaves for the person who helps. */
    private fun Pen.report(p: Float) {
        val pop = back(cl((p - 5.6f) / 0.4f))
        val fly = ease((p - 6.6f) / 2f)
        if (p < 5.6f || fly >= 1f) return
        val x = mix(PostX[2] + 34f, 312f, fly)
        val y = mix(72f, 20f, fly) - sin(fly * Pi) * 18f
        at(x, y, -0.12f + fly * 0.7f, pop, pop) {
            rr(-7f, -9f, 14f, 18f, 2f)
            fill(colors.paper)
            stroke(colors.ink, 0.9f, 1.3f)
            seg(-4f, -5f, 4f, -5f, colors.ink, 0.5f, 1.1f)
            seg(-4f, -1.5f, 4f, -1.5f, colors.ink, 0.5f, 1.1f)
            circ(-2.5f, 3.5f, 1.8f)
            fill(colors.danger)
            seg(1f, 3.5f, 4f, 3.5f, colors.ink, 0.5f, 1.1f)
        }
    }
}
