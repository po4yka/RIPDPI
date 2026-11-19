// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.abs

private const val ButtonX = 58f
private const val ButtonY = 92f
private const val GlobeX = 256f
private const val GlobeY = Ground - 28f
private const val WallX = 178f
private const val WallW = 20f
private const val PressOn = 1.4f
private const val PressOff = 6.2f
private const val Riders = 3

// The share of the tunnel at which it is in the middle of the wall.
private const val WallShare = 0.69f

/**
 * Connect. The ghost presses the one button of the device. The button lights, a key shows on the screen, and a
 * tunnel grows from the device through the wall to the open internet; small ghosts ride it and turn to outlines
 * in the wall. The second press takes the tunnel back.
 */
internal class ConnectScene(
    parts: Parts,
) : Scene(parts) {
    private val loop = Loop(9f)
    private val press = Spring()
    private val lit = Spring()
    private val arrive = Spring()
    private val riders = FloatArray(Riders)
    private var grown = 0f

    override val stillAt = 4f

    override fun enter(frame: Frame) {
        super.enter(frame)
        loop.reset()
        grown = 0f
        lit.x = 0f
        lit.v = 0f
    }

    override fun Pen.draw(frame: Frame) {
        val dt = frame.dt
        loop.step(since)
        val on = loop.at >= PressOn && loop.at < PressOff
        if (loop.passed(PressOn) || loop.passed(PressOff)) {
            press.v += 9f
            parts.ring(ButtonX, ButtonY, colors.glow, 10f)
        }
        press.step(0f, 260f, 14f, dt)
        lit.step(if (on) 1f else 0f, 120f, 14f, dt)
        arrive.step(0f, 160f, 9f, dt)
        grown = cl(grown + if (on) dt else -dt * 2f)
        sky(0.7f)
        rise(0f) { device(cl(lit.x)) }
        rise(4f) { globe(GlobeX, GlobeY, 13f, arrive.x, 0.3f + grown * 0.7f) }
        rise(2f) { wall(WallX, Ground - 72f, WallW) }
        tunnel(on)
        presser()
    }

    private fun tubeX(u: Float): Float = 86f * (1f - u) * (1f - u) + 300f * u * (1f - u) + 244f * u * u

    private fun tubeY(u: Float): Float = 80f * (1f - u) * (1f - u) + 124f * u * (1f - u) + 110f * u * u

    private fun Pen.device(k: Float) {
        shadow(ButtonX, Ground + 1f, 30f)
        phone(ButtonX, Ground, 56f, 96f, 0f, colors.night)
        // The key of a connection that is up.
        at(ButtonX, 62f) {
            circ(-5f, 0f, 3f)
            stroke(colors.glowHigh, k, 1.4f)
            seg(-2f, 0f, 7f, 0f, colors.glowHigh, k, 1.4f)
            seg(4f, 0f, 4f, 2.6f, colors.glowHigh, k, 1.4f)
            seg(7f, 0f, 7f, 2.6f, colors.glowHigh, k, 1.4f)
        }
        val pressed = 1f - cl(press.x * 0.25f, -0.2f, 0.2f)
        circ(ButtonX, ButtonY, 17f)
        fill(colors.tone(0.2f))
        circ(ButtonX, ButtonY, 13f * pressed)
        fill(colors.tone(0.42f))
        fill(colors.signal, k)
        circ(ButtonX, ButtonY, 13f + abs(press.x) * 20f)
        stroke(colors.glow, cl(abs(press.x) * 1.4f), 1.4f)
        arc(ButtonX, ButtonY, 5.5f * pressed, -1f, 4.1f)
        stroke(colors.nightLight, 1f, 1.6f)
        seg(ButtonX, ButtonY - 7f, ButtonX, ButtonY - 1f, colors.nightLight, 1f, 1.6f)
        rr(46f, 118f, 24f, 3f, 1.5f)
        fill(colors.tone(0.35f))
        fill(colors.signal, k * 0.8f)
    }

    private fun Pen.tunnel(on: Boolean) {
        if (grown <= 0.01f) {
            riders.fill(0f)
            return
        }
        if (grown > WallShare) {
            val k = cl((grown - WallShare) * 8f)
            circ(tubeX(WallShare), tubeY(WallShare), 7.5f * k)
            fill(colors.paper)
            stroke(colors.signal, k, 1.4f)
        }
        shape {
            moveTo(tubeX(0f), tubeY(0f))
            for (k in 1..24) {
                val u = k / 24f * grown
                lineTo(tubeX(u), tubeY(u))
            }
        }
        stroke(colors.signal, 0.2f, 7f)
        stroke(colors.signal, 0.9f, 1.4f, dash = 3f, gap = 4f, phase = -time * 18f)
        if (grown < 1f) {
            riders.fill(0f)
            if (on) {
                star(tubeX(grown), tubeY(grown), 4f, time * 3f)
                fill(colors.signal)
            }
            return
        }
        repeat(Riders) { j ->
            val u = fract(time * 0.26f + j / Riders.toFloat())
            if (u < riders[j]) {
                arrive.v += 4f
                parts.ring(tubeX(1f), tubeY(1f), colors.signal, 3f)
            }
            riders[j] = u
            ghost(
                Ghost(
                    x = tubeX(u),
                    y = tubeY(u) + 7f,
                    s = 0.42f,
                    a = cl(u * 8f) * cl((1f - u) * 8f),
                    ph = cl(1f - abs(tubeX(u) - WallX - WallW / 2f) / 14f),
                    lag = 0.5f,
                    id = j,
                    still = true,
                ),
            )
        }
    }

    /** The ghost presses the button from its place: a dotted line runs from it to the button for each press. */
    private fun Pen.presser() {
        val x = 122f
        val y = Ground - 10f
        val use = cl(abs(press.x) * 2f)
        if (use > 0.05f) {
            shape {
                moveTo(x - 12f, y - 14f)
                quadraticTo((x + ButtonX) / 2f - 4f, (y + ButtonY) / 2f - 30f, ButtonX, ButtonY)
            }
            stroke(colors.signal, use * 0.7f, 1.3f, dash = 1.5f, gap = 3.5f, phase = -time * 14f)
            star(ButtonX, ButtonY, 3.2f * use, time * 3f)
            fill(colors.nightLight, use)
        }
        shadow(x, Ground + 1f, 12f)
        ghost(
            Ghost(
                x = x,
                y = y,
                s = 1.1f,
                look = if (grown > 0.5f) 1f else -1f,
                lookY = if (grown > 0.5f) -0.6f else 0f,
                eye = if (use > 0.4f) Eye.Narrow else Eye.Open,
                id = 6,
            ),
        )
    }
}
