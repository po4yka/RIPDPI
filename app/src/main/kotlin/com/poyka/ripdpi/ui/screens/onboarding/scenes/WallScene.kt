// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

private const val Pi = PI.toFloat()
private const val WallX = 188f
private const val WallW = 20f
private const val WallMid = WallX + WallW / 2f

// The apps of the device: the place and the size of each.
private val AppPlaces =
    floatArrayOf(
        52f,
        50f,
        12f,
        86f,
        40f,
        11f,
        114f,
        60f,
        12f,
        42f,
        86f,
        11f,
        78f,
        78f,
        13f,
        108f,
        94f,
        11f,
        66f,
        113f,
        10f,
    )

/**
 * The wall. A brick wall with wire and a scanner stands between the apps and the open internet. Whole device: a
 * loop holds every app, and each app sends a ghost that passes through the wall. Proxy only: the loop tightens on
 * two apps. The plain packets of the other apps roll to the wall; the scanner sees them, and they stop.
 */
internal class WallScene(
    parts: Parts,
) : Scene(parts) {
    private class App(
        val index: Int,
    ) {
        val x = AppPlaces[index * 3] * 0.84f - 4f
        val y = AppPlaces[index * 3 + 1]
        val r = AppPlaces[index * 3 + 2] * 0.9f
        val inProxy = index == 4 || index == 5
        val bump = Spring()
        var nowX = x
        var nowY = y
    }

    private class Mover(
        val ghost: Boolean,
        val lane: Float,
        val id: Int,
        val fromX: Float,
        val fromY: Float,
    ) {
        var t = 0f
        var x = fromX + 34f
        var ringed = false
        var counted = false
        var hitAt = -1f
        var dead = false
    }

    private val apps = List(AppPlaces.size / 3) { App(it) }
    private val movers = ArrayList<Mover>()
    private val front = ArrayList<Ghost>()
    private val proxy = Spring()
    private val hit = Spring()
    private val arrive = Spring()
    private val scanner = Spring(WallMid)
    private var cracked = 0f
    private var due = 0f
    private var seenX = 0f
    private var seenY = 0f
    private var seen = false

    override fun enter(frame: Frame) {
        super.enter(frame)
        movers.clear()
        cracked = 0f
        proxy.x = if (frame.proxy) 1f else 0f
        proxy.v = 0f
    }

    override fun Pen.draw(frame: Frame) {
        val dt = frame.dt
        proxy.step(if (frame.proxy) 1f else 0f, 110f, 14f, dt)
        hit.step(0f, 200f, 12f, dt)
        arrive.step(0f, 160f, 9f, dt)
        cracked = maxOf(0f, cracked - dt * 0.025f)
        val s = cl(proxy.x)
        sky(0.7f)
        rise(0f) {
            loop(s)
            apps.forEach { app(it, s, dt) }
        }
        rise(4f) {
            globe(262f, Ground - 28f, 13f, arrive.x)
            repeat(2) { i ->
                val u = fract(time * 0.3f + i * 0.5f)
                arc(262f, Ground - 28f, 15f + u * 14f, -2.4f, -0.7f)
                stroke(colors.signal, (1f - u) * 0.5f, 1.2f)
            }
        }
        send(s, dt)
        front.clear()
        seen = false
        movers.forEach { if (it.ghost) passer(it, dt) else plain(it, dt) }
        movers.removeAll { it.dead || it.t > 7f }
        rise(2f) { wallAndScanner(dt) }
        front.forEach { ghost(it) }
    }

    /** The loop of rope around the apps that take the tunnel. It tightens on two of them for Proxy only. */
    private fun Pen.loop(s: Float) {
        val cx = mix(64f, 76f, s)
        val cy = mix(78f, 86f, s)
        val rx = mix(56f, 31f, s)
        val ry = mix(54f, 23f, s)
        val rot = mix(0f, 0.44f, s)
        shape {
            repeat(49) { k ->
                val angle = k / 48f * 2f * Pi
                val wobble =
                    1f + 0.035f * sin(angle * 3f + time * 1.6f) + 0.02f * sin(angle * 7f - time * 2.3f) +
                        proxy.v * 0.02f * sin(angle * 2f)
                val x = cos(angle) * rx * wobble
                val y = sin(angle) * ry * wobble
                val px = cx + x * cos(rot) - y * sin(rot)
                val py = cy + x * sin(rot) + y * cos(rot)
                if (k == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
        fill(colors.signal, 0.09f)
        stroke(colors.signal, 0.9f, 1.7f, dash = 5f, gap = 4f, phase = -time * 9f)
    }

    private fun Pen.app(
        app: App,
        s: Float,
        dt: Float,
    ) {
        val dx = app.x - 76f
        val dy = app.y - 86f
        val far = hypot(dx, dy).coerceAtLeast(1f)
        val push = if (app.inProxy) 0f else s * 8f
        app.bump.step(0f, 200f, 12f, dt)
        app.nowX = app.x + dx / far * push + sin(time * 1.1f + app.index * 1.7f) * 1.5f
        app.nowY = app.y + dy / far * push + cos(time * 0.9f + app.index * 2.3f) * 1.8f
        val a = mix(1f, if (app.inProxy) 1f else 0.32f, s)
        val r = app.r * (1f + app.bump.x) * inn(1f + app.index * 0.6f)
        circ(app.nowX + 1f, app.nowY + 2f, r)
        fill(colors.ink, 0.12f * a)
        circ(app.nowX, app.nowY, r)
        fill(colors.ink, 0.94f * a)
        if (r > 6f) glyph(app.index, app.nowX, app.nowY, a)
    }

    private fun send(
        s: Float,
        dt: Float,
    ) {
        due += dt
        if (due < 0.46f || since < 0.9f) return
        due = 0f
        val app = apps[(parts.rnd(apps.size.toFloat())).toInt().coerceAtMost(apps.size - 1)]
        val asGhost = s < 0.5f || app.inProxy
        if (!asGhost && parts.rnd() > 0.7f) return
        app.bump.v += 1.4f
        val lane = if (asGhost) 50f + parts.rnd(66f) else Ground - 4f
        movers += Mover(asGhost, lane, parts.rnd(9f).toInt(), app.nowX, app.nowY)
    }

    /** A ghost slows at the wall, turns to an outline, and comes out on the other side. */
    private fun Pen.passer(
        mover: Mover,
        dt: Float,
    ) {
        mover.t += dt
        val t = mover.t
        val u = cl(t / 0.9f)
        val near = exp(-((mover.x - WallMid) / 19f) * ((mover.x - WallMid) / 19f))
        if (t > 0.9f) mover.x += dt * 66f * (1f - 0.55f * near)
        val x = if (t < 0.9f) mix(mover.fromX, mover.fromX + 34f, ease(u)) else mover.x
        val y = mix(mover.fromY, mover.lane, ease(u))
        if (near > 0.7f && !mover.ringed) {
            mover.ringed = true
            parts.ring(WallMid, y - 8f, colors.signal, 3f)
            cracked = minOf(1f, cracked + 0.09f)
            if (parts.rnd() < 0.5f) parts.chips(WallMid, y - 8f, colors.ink, 2)
        }
        if (x > 250f && !mover.counted) {
            mover.counted = true
            arrive.v += 3f
        }
        if (x > 266f) mover.dead = true
        val pose =
            Ghost(
                x = x,
                y = y,
                s = 0.52f,
                a = cl(t * 5f) * cl((268f - x) / 14f),
                ph = near * 0.95f,
                lag = if (t < 0.9f) 0.2f else 0.42f * (1f - 0.5f * near),
                look = 1f,
                eye = if (near > 0.3f) Eye.Narrow else Eye.Open,
                id = mover.id,
            )
        if (x > WallMid) front += pose else ghost(pose)
    }

    /** A plain packet rolls to the wall, is seen, and stops. */
    private fun Pen.plain(
        mover: Mover,
        dt: Float,
    ) {
        mover.t += dt
        val t = mover.t
        val u = cl(t / 0.7f)
        val stop = WallX - 5f - (mover.id % 3) * 7f
        var x = if (t < 0.7f) mix(mover.fromX, mover.fromX + 26f, u) else mover.fromX + 26f + (t - 0.7f) * 70f
        val y = if (t < 0.7f) mix(mover.fromY, Ground - 4f, u * u) else Ground - 4f
        val rolls = t > 0.7f && mover.hitAt < 0f
        if (rolls && x > WallX - 60f && x <= stop) {
            seen = true
            seenX = x
            seenY = y
        }
        if (x > stop) {
            x = stop
            if (mover.hitAt < 0f) {
                mover.hitAt = t
                hit.v += 1.2f
                parts.spark(WallX - 2f, Ground - 8f, colors.danger, 3, 40f)
            }
        }
        val stopped = if (mover.hitAt >= 0f) t - mover.hitAt else 0f
        if (stopped > 1.4f) mover.dead = true
        val a = cl(t * 5f) * cl(1.4f - stopped)
        at(x, y, if (mover.hitAt >= 0f) 0f else t * 5f) {
            rr(-3.5f, -3.5f, 7f, 7f, 1.6f)
            fill(colors.mid, a)
            stroke(if (mover.hitAt >= 0f) colors.danger else colors.ink, a * 0.8f, 1f)
        }
        if (mover.hitAt >= 0f && stopped < 0.8f) {
            val up = stopped * 8f
            seg(x - 2.5f, y - 13f - up, x + 2.5f, y - 8f - up, colors.danger, 1f - stopped / 0.8f, 1.5f)
            seg(x - 2.5f, y - 8f - up, x + 2.5f, y - 13f - up, colors.danger, 1f - stopped / 0.8f, 1.5f)
        }
    }

    /** The scanner on the wall locks onto a plain packet. It does not see a ghost. */
    private fun Pen.wallAndScanner(dt: Float) {
        val idle = WallMid + sin(time * 1.4f) * 6f
        scanner.step(if (seen) WallX + 3f else idle, if (seen) 120f else 30f, if (seen) 16f else 8f, dt)
        at(-hit.x * 1.2f, 0f) {
            wall(WallX, 34f, WallW, cracked)
            circ(WallMid, 58f, 6.5f)
            fill(colors.paper)
            stroke(colors.danger, 1f, 2f)
            seg(WallMid - 3.5f, 58f, WallMid + 3.5f, 58f, colors.danger, 1f, 2f)
            rr(WallX + 2f, 22f, WallW - 4f, 7f, 3.5f)
            fill(colors.ink, 0.95f)
            circ(scanner.x, 25.5f, if (seen) 2.8f else 2.2f)
            fill(colors.danger)
            if (seen) {
                seg(scanner.x, 25.5f, seenX, seenY, colors.danger, 0.55f, 1f)
                circ(seenX, seenY, 6f)
                stroke(colors.danger, 0.8f, 1.1f)
            }
        }
    }
}

/** The mark of an app, in the color of the page. */
private fun Pen.glyph(
    index: Int,
    x: Float,
    y: Float,
    a: Float,
) {
    at(x, y) {
        when (index) {
            0 -> {
                rr(-5f, -4.5f, 10f, 7f, 2.5f)
                fill(colors.paper, a)
                shape {
                    moveTo(-2f, 2f)
                    lineTo(-3.5f, 5.5f)
                    lineTo(1.5f, 2f)
                }
                fill(colors.paper, a)
            }

            1 -> {
                shape {
                    moveTo(-3f, -5f)
                    lineTo(5f, 0f)
                    lineTo(-3f, 5f)
                    close()
                }
                fill(colors.paper, a)
            }

            2 -> {
                circ(-2f, 3f, 2.6f)
                fill(colors.paper, a)
                seg(0.6f, 3f, 0.6f, -5f, colors.paper, a, 1.5f)
                seg(0.6f, -5f, 4.5f, -3.5f, colors.paper, a, 1.5f)
            }

            3 -> {
                circ(0f, 0f, 5f)
                stroke(colors.paper, a, 1.4f)
                ell(0f, 0f, 2.2f, 5f)
                stroke(colors.paper, a, 1.4f)
                seg(-5f, 0f, 5f, 0f, colors.paper, a, 1.2f)
            }

            4 -> {
                rr(-6f, -3.5f, 12f, 8.5f, 2f)
                fill(colors.paper, a)
                circ(0f, 0.8f, 2.4f)
                fill(colors.ink, a)
                rr(-2f, -5.5f, 4f, 2.5f, 1f)
                fill(colors.paper, a)
            }

            5 -> {
                rr(-5.5f, -4f, 11f, 8f, 1.5f)
                stroke(colors.paper, a, 1.4f)
                shape {
                    moveTo(-5f, -3.5f)
                    lineTo(0f, 1f)
                    lineTo(5f, -3.5f)
                }
                stroke(colors.paper, a, 1.4f)
            }

            else -> {
                star(0f, 0f, 5.5f, -0.39f)
                fill(colors.paper, a)
            }
        }
    }
}
