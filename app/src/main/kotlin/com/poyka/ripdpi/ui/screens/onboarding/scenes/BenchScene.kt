// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val Pi = PI.toFloat()
private const val BodyY = 58f
private const val BodyH = 78f
private const val Faders = 5
private const val Dials = 4

/**
 * The bench. Guided is one dial and one button, and the ghost waits beside the console. Advanced opens the wings
 * of the console and grows faders, dials, a scope, lamps, and a meter. The ghost moves a control without a touch:
 * a dotted line runs from the ghost to the control that it works on, and the scope and the meter follow the faders.
 */
internal class BenchScene(
    parts: Parts,
) : Scene(parts) {
    private val open = Spring()
    private val faders = List(Faders) { Spring(0.3f + it * 0.1f) }
    private val faderTargets = FloatArray(Faders) { 0.3f + it * 0.1f }
    private val dials = List(Dials) { Spring() }
    private val dialTargets = FloatArray(Dials)
    private val button = Spring()
    private val ghostX = Spring(254f)
    private val ghostY = Spring(Ground - 22f)
    private val controlX = FloatArray(Faders + Dials)
    private val controlY = FloatArray(Faders + Dials)
    private var attends = -1
    private var beat = -1

    override fun enter(frame: Frame) {
        super.enter(frame)
        open.x = if (frame.advanced) 1f else 0f
        open.v = 0f
        ghostX.x = if (frame.advanced) 150f else 254f
        ghostY.x = if (frame.advanced) BodyY - 4f else Ground - 22f
    }

    private fun grown(order: Float): Float = back(cl(cl(open.x) * 2.4f - 0.25f - order * 0.11f))

    override fun Pen.draw(frame: Frame) {
        open.step(if (frame.advanced) 1f else 0f, 120f, 14f, frame.dt)
        val a = cl(open.x)
        attend(a)
        faders.forEachIndexed { i, fader -> fader.step(faderTargets[i], 70f, 11f, frame.dt) }
        dials.forEachIndexed { i, dial -> dial.step(dialTargets[i], 50f, 9f, frame.dt) }
        button.step(0f, 260f, 14f, frame.dt)
        val level = faders.sumOf { it.x.toDouble() }.toFloat() / Faders
        sky(0.7f)
        shadow(150f, Ground + 1f, 70f + a * 40f, 0.08f)
        wings(a, level)
        body()
        guided(cl(1f - a * 1.5f))
        fadersAndDials()
        scopeAndLamps(level)
        cable(a)
        ghostAtWork(a, frame.dt)
    }

    /** The ghost takes a new control at each beat, and gives it a new value. */
    private fun attend(a: Float) {
        val now = (since / 1.15f).toInt()
        if (now == beat) return
        beat = now
        attends = if (a > 0.5f) now % (Faders + Dials) else -1
        when {
            attends in 0 until Faders -> faderTargets[attends] = 0.12f + parts.rnd(0.76f)
            attends >= Faders -> dialTargets[attends - Faders] += 1f + parts.rnd(2f)
            a < 0.5f && now % 3 == 0 -> button.v += 9f
        }
    }

    private fun Pen.wings(
        a: Float,
        level: Float,
    ) {
        listOf(mix(82f, 34f, ease(a)), mix(170f, 218f, ease(a))).forEach { x ->
            rr(x, BodyY + 8f, 48f, BodyH - 14f, 10f)
            fill(colors.paper)
            fill(colors.ink, 0.16f)
            stroke(colors.ink, 0.75f, 1.4f)
        }
        if (a <= 0.3f) return
        repeat(3) { i ->
            val s = grown(6f + i)
            val y = BodyY + 22f + i * 17f
            rr(42f, y, 16f * s, 8f * s, 4f)
            fill(colors.ink, 0.25f * s)
            circ(46f + (if (sin(time * 0.9f + i * 2f) > 0f) 8f else 0f) * s, y + 4f * s, 3.4f * s)
            fill(if (i == 1) colors.signal else colors.ink, s)
        }
        // The meter follows the level of the faders.
        val s = grown(8f)
        arc(246f, BodyY + 44f, 15f * s, Pi, 2f * Pi)
        stroke(colors.ink, 0.6f * s, 1.4f)
        repeat(5) { k ->
            val tick = Pi + k * Pi / 4f
            seg(
                246f + cos(tick) * 12f * s,
                BodyY + 44f + sin(tick) * 12f * s,
                246f + cos(tick) * 15f * s,
                BodyY + 44f + sin(tick) * 15f * s,
                colors.ink,
                0.6f * s,
                1.2f,
            )
        }
        val needle = -Pi + 0.3f + level * 2.5f + sin(time * 7f) * 0.04f
        seg(
            246f,
            BodyY + 44f,
            246f + cos(needle) * 12f * s,
            BodyY + 44f + sin(needle) * 12f * s,
            colors.signal,
            s,
            1.6f,
        )
        circ(246f, BodyY + 44f, 2f * s)
        fill(colors.ink, s)
    }

    private fun Pen.body() {
        listOf(86f, 214f).forEach { x ->
            rr(x - 7f, Ground - 8f, 14f, 8f, 2f, 2f, 4f, 4f)
            fill(colors.ink, 0.9f)
        }
        rr(70f, BodyY, 160f, BodyH, 14f)
        fill(colors.paper)
        fill(colors.ink, 0.09f)
        stroke(colors.ink, 0.85f, 1.6f)
        rr(76f, BodyY + 6f, 148f, BodyH - 12f, 10f)
        stroke(colors.ink, 0.14f, 1f)
        listOf(80f, 220f).forEach { x ->
            listOf(BodyY + 10f, BodyY + BodyH - 10f).forEach { y ->
                circ(x, y, 1.5f)
                fill(colors.ink, 0.35f)
            }
        }
    }

    /** One dial and one button. The button gives when the ghost presses it from its place. */
    private fun Pen.guided(g: Float) {
        knob(116f, BodyY + 40f, 19f * g, sin(time * 0.8f) + sin(time * 2.3f) * 0.15f, g, ticks = true)
        if (g <= 0.01f) return
        val pressed = 1f - cl(button.x * 0.25f, -0.2f, 0.2f)
        circ(184f, BodyY + 40f, 17f * g)
        fill(colors.ink, 0.92f * g)
        circ(184f, BodyY + 40f, 12f * g * pressed)
        fill(colors.signal, g)
        circ(184f, BodyY + 40f, (12f + abs(button.x) * 22f) * g)
        stroke(colors.signal, cl(abs(button.x) * 1.4f) * g, 1.4f)
        arc(184f, BodyY + 40f, 5.5f * g * pressed, -1f, 4.1f)
        stroke(colors.paper, g, 1.6f)
        seg(184f, BodyY + 33f, 184f, BodyY + 39f, colors.paper, g, 1.6f)
    }

    private fun Pen.fadersAndDials() {
        repeat(Faders) { i ->
            val s = grown(i.toFloat())
            val x = 88f + i * 13f
            val y = BodyY + 16f + (4f + faders[i].x * 36f) * s
            controlX[i] = x
            controlY[i] = y
            if (s > 0.01f) {
                seg(x, BodyY + 16f, x, BodyY + 16f + 44f * s, colors.ink, 0.28f * s, 2.2f)
                repeat(5) { k ->
                    seg(
                        x + 3.5f,
                        BodyY + 18f + k * 10f * s,
                        x + 5.5f,
                        BodyY + 18f + k * 10f * s,
                        colors.ink,
                        0.25f * s,
                        1f,
                    )
                }
                rr(x - 5f, y - 3.5f, 10f, 7f, 2.5f)
                fill(colors.ink, s)
                seg(x - 3f, y, x + 3f, y, colors.signal, s, 1.2f)
            }
        }
        repeat(Dials) { i ->
            val x = 160f + (i % 2) * 17f
            val y = BodyY + 22f + (i / 2) * 20f
            controlX[Faders + i] = x
            controlY[Faders + i] = y
            knob(x, y, 6.5f * grown(3f + i), dials[i].x, cl(grown(3f + i)))
        }
    }

    /** The scope and the lamps show the level of the faders. */
    private fun Pen.scopeAndLamps(level: Float) {
        val s = grown(5f)
        if (s > 0.01f) {
            rr(190f, BodyY + 13f, 30f * s, 26f * s, 5f)
            fill(colors.night, cl(s))
            shape {
                repeat(41) { k ->
                    val t = k / 40f * 2f * Pi
                    val x = 205f + sin(t * 3f + time * 1.4f) * 11f * s
                    val y = BodyY + 26f + sin(t * 2f) * (2f + level * 10f) * s
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            stroke(colors.glow, cl(s), 1.3f)
        }
        val lit = (level * 12f).roundToInt()
        repeat(12) { i ->
            val lamp = grown(6f + i * 0.25f)
            if (lamp > 0.01f) {
                circ(192f + (i % 4) * 8.6f, BodyY + 46f + (i / 4) * 7.5f, 2.3f * lamp)
                fill(if (i < lit) colors.signal else colors.ink, cl(lamp) * if (i < lit) 1f else 0.3f)
            }
        }
    }

    private fun Pen.cable(a: Float) {
        if (a <= 0.2f) return
        val s = cl((a - 0.2f) / 0.6f)
        shape {
            moveTo(266f, BodyY + 50f)
            for (k in 1..20) {
                val t = k / 20f * s
                lineTo(
                    266f + t * 22f + sin(t * 16f) * 3.5f,
                    BodyY + 50f + t * (Ground - BodyY - 50f) + cos(t * 16f) * 2f,
                )
            }
        }
        stroke(colors.ink, s * 0.8f, 1.6f)
    }

    private fun Pen.ghostAtWork(
        a: Float,
        dt: Float,
    ) {
        val works = a > 0.5f
        val targetX =
            if (works && attends >= 0) {
                controlX[attends]
            } else if (works) {
                150f
            } else {
                184f
            }
        val targetY =
            if (works && attends >= 0) {
                controlY[attends]
            } else if (works) {
                BodyY + 30f
            } else {
                BodyY + 40f
            }
        val homeX = if (works) cl(targetX, 92f, 208f) else 254f
        ghostX.step(homeX, 38f, 9f, dt)
        ghostY.step(if (works) BodyY - 4f else Ground - 24f, 38f, 9f, dt)
        val use = if (works) cl(1f - abs(ghostX.x - homeX) / 14f) else cl(abs(button.x) * 2f)
        if (use > 0.05f) {
            val fromX = ghostX.x - if (works) 0f else 12f
            val fromY = ghostY.x - if (works) 2f else 14f
            shape {
                moveTo(fromX, fromY)
                quadraticTo(
                    (ghostX.x + targetX) / 2f - if (works) 0f else 14f,
                    (ghostY.x + targetY) / 2f - if (works) 0f else 26f,
                    targetX,
                    targetY,
                )
            }
            stroke(colors.signal, use * 0.7f, 1.3f, dash = 1.5f, gap = 3.5f, phase = -time * 14f)
            star(targetX, targetY, 3.2f * use, time * 3f)
            fill(colors.signal, use)
        }
        ghost(
            Ghost(
                x = ghostX.x,
                y = ghostY.x,
                s = mix(1.05f, 0.85f, a),
                lag = ghostX.v / 60f,
                look = if (works) cl((targetX - ghostX.x) / 8f, -1f, 1f) else -1f,
                lookY = if (works) 1f else 0f,
                eye = if (use > 0.4f) Eye.Narrow else Eye.Open,
                id = 4,
            ),
        )
    }
}
