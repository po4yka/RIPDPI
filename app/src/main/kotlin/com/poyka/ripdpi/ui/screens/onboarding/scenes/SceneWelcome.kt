// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sin

private const val Pi = PI.toFloat()

/**
 * Rest in peace, DPI. The scene opens as the app icon: the ghost inside the tombstone. The ghost leaves the stone
 * through its top, the carving shows, the stone cracks and leans, and a crow lands on it.
 */
internal class WelcomeScene(
    parts: Parts,
) : Scene(parts) {
    private val tilt = Spring()
    private val bob = Spring()
    private var chipped = 0
    private var landed = false
    private var cawing = false

    override val stillAt = 7f

    override fun enter(frame: Frame) {
        super.enter(frame)
        tilt.x = 0f
        tilt.v = 0f
        chipped = 0
        landed = false
        cawing = false
    }

    override fun Pen.draw(frame: Frame) {
        val out = ease((since - 1.25f) / 1.5f)
        val cracked = ease((since - 2.4f) / 1.3f)
        sky(0.8f)
        rise(1f) { fence(Ground - 1f, 0.22f) }
        rise(2f) { tree(42f, Ground, 1f, colors.ink, 0.85f) }
        ell(150f, Ground + 3f, 74f, 11f)
        fill(colors.ink, 0.16f)
        tilt.step(if (cracked > 0f) -0.045f else 0f, 60f, 5f, frame.dt)
        bob.step(0f, 220f, 9f, frame.dt)
        chipStone()
        leaves()
        // The stone trembles before the ghost leaves it.
        val tremble = if (since > 1f && since < 1.4f) sin(since * 90f) * 0.9f else 0f
        at(tremble, bob.x) {
            tomb(150f, Ground + 1f, 70f, 88f, tilt.x, carving = ease((since - 1.6f) / 0.9f), cracked = cracked)
            val hollow = if (out > 0f) cl(1f - out * 1.5f) * 0.55f else 0f
            ghost(Ghost(150f, Ground - 21f, 1.42f, a = hollow, still = true, id = 1))
        }
        crowComes()
        rise(3f) {
            grass(102f, Ground + 2f, 5)
            grass(190f, Ground + 3f, 6)
            grass(232f, Ground + 1f, 4)
            grass(68f, Ground + 2f, 4)
        }
        repeat(3) { i ->
            val u = fract(time * 0.16f + i / 3f)
            wisp(124f + i * 22f + sin(u * 7f + i) * 5f, Ground - 6f - u * 70f, 1.6f, cracked * sin(u * Pi) * 0.8f)
        }
        theGhost(out)
    }

    private fun Pen.chipStone() {
        val due = floatArrayOf(2.4f, 2.85f, 3.3f)
        if (chipped < due.size && since > due[chipped]) {
            parts.chips(158f + chipped * 5f, Ground - 80f + chipped * 12f, colors.ink)
            if (chipped == 0) parts.dust(160f, Ground - 84f, colors.mid)
            chipped++
        }
    }

    private fun Pen.leaves() {
        repeat(4) { i ->
            val u = fract(time * 0.11f + i * 0.27f)
            val y = Ground - 3f - abs(sin(u * 9f + i)) * 10f - i * 2f
            at(-10f + u * 330f, y, u * 14f + i) {
                ell(0f, 0f, 3f, 1.4f)
                fill(colors.ink, 0.45f * sin(u * Pi))
            }
        }
    }

    /** The crow comes down on beating wings, and the stone gives under it. Later it calls. */
    private fun Pen.crowComes() {
        val flight = ease((since - 3.9f) / 1f)
        if (flight <= 0f) return
        val x = 158f - (1f - flight) * 110f - sin(flight * Pi) * 20f
        val y = Ground - 87f + bob.x - (1f - flight) * (1f - flight) * 58f
        if (flight >= 1f && !landed) {
            landed = true
            bob.v += 22f
            parts.dust(158f, Ground - 86f, colors.mid, 3)
        }
        val call = (since - 5.4f) % 6.5f
        cawing = since > 5.4f && call < 0.5f
        if (flight < 1f) {
            val beat = sin(since * 26f)
            listOf(1f, -1f).forEach { side ->
                shape {
                    moveTo(x - 2f * side, y - 5f)
                    quadraticTo(x + 5f * side, y - 8f - 9f * beat, x + 14f * side, y - 5f - 5f * beat)
                    lineTo(x + 4f * side, y - 4f)
                    close()
                }
                fill(colors.ink)
            }
        }
        val turn = sin(time * 0.8f)
        val head =
            when {
                cawing -> -0.6f
                turn > 0.6f -> 1f
                turn < -0.7f -> -1f
                else -> 0f
            }
        crow(x, y, head)
        if (cawing) {
            repeat(2) { k ->
                arc(x - 12f, y - 10f, 5f + k * 4f + call * 8f, 2.5f, 3.9f)
                stroke(colors.ink, (1f - call * 2f) * 0.7f, 1.2f)
            }
        }
    }

    /** Out through the top of the stone, and then to its place beside it. */
    private fun Pen.theGhost(out: Float) {
        val idle = cl((since - 3f) / 1f)
        val up = cl(out / 0.45f)
        val across = ease((out - 0.45f) / 0.55f)
        val x = mix(150f, 214f, across) + sin(time * 0.5f) * 6f * idle
        val y =
            mix(Ground - 21f, Ground - 100f, ease(up)) + across * 66f - sin(across * Pi) * 8f + sin(time) * 2f * idle
        shadow(x, Ground + 2f, 10f * across, 0.1f * across)
        val watches = sin(time * 0.45f) > 0.2f
        val look =
            when {
                cawing -> -1f
                idle > 0f && watches -> -1f
                else -> 0f
            }
        ghost(
            Ghost(
                x = x,
                y = y,
                s = mix(1.42f, 1.22f, out),
                ph = sin(cl(out * 1.5f) * Pi) * 0.85f,
                lag = sin(across * Pi) * 0.9f,
                tilt = -sin(up * Pi) * 0.06f,
                look = look,
                lookY = if (out > 0f && up < 1f) -1f else 0f,
                eye = if (since > 0.55f && since < 0.72f) Eye.Shut else Eye.Open,
                id = 1,
                still = out < 0.02f,
            ),
        )
    }
}

/**
 * Unseen. A camera sweeps a red light across the ground. Each ghost that the light touches turns to an outline
 * and loses its shadow, so the camera finds nothing. Nothing leaves the limit around the phone.
 */
internal class UnseenScene(
    parts: Parts,
) : Scene(parts) {
    private class Hidden(
        val x: Float,
        val y: Float,
        val s: Float,
        val id: Int,
    ) {
        val fade = Spring()
    }

    private val ghosts =
        listOf(
            Hidden(112f, Ground - 12f, 1.15f, 1),
            Hidden(66f, Ground - 22f, 0.6f, 2),
            Hidden(
                172f,
                Ground - 34f,
                0.7f,
                3,
            ),
        )

    override fun Pen.draw(frame: Frame) {
        val p = time % 8f
        val beamX = beamAt(p)
        val puzzled = (p > 2.9f && p < 3.9f) || (p > 6.8f && p < 7.8f)
        sky(0.8f)
        rise(1f) {
            seg(CameraX + 8f, CameraY + 6f, CameraX + 8f, Ground, colors.ink, 0.9f, 3f)
            rr(CameraX - 1f, Ground - 4f, 18f, 4f, 2f)
            fill(colors.ink, 0.9f)
            seg(CameraX + 8f, CameraY + 40f, CameraX - 6f, CameraY + 52f, colors.ink, 0.5f, 1.4f)
        }
        beam(beamX)
        limit()
        rise(0f) {
            shadow(34f, Ground + 1f, 14f)
            phone(34f, Ground, 24f, 44f, 0.04f, colors.night)
            circ(34f, Ground - 22f, 3f + sin(time * 2f) * 0.6f)
            fill(colors.signal)
            circ(34f, Ground - 22f, 7f)
            stroke(colors.signal, 0.4f, 1f)
        }
        ghosts.forEach { hide(it, beamX, frame.dt) }
        camera(beamX, puzzled)
        if (puzzled) {
            val u = if (p > 6f) p - 6.8f else p - 2.9f
            text("?", CameraX - 8f, CameraY - 18f - u * 5f, 13f, colors.ink, cl(u * 5f) * cl((1f - u) * 4f), TextCarved)
        }
    }

    /** The camera sweeps, stops, and looks twice at the place where a ghost was. */
    private fun beamAt(p: Float): Float =
        when {
            p < 2.8f -> mix(244f, 52f, ease(p / 2.8f))
            p < 3.9f -> 52f + sin((p - 2.8f) * 11f) * 14f * (1f - (p - 2.8f) / 1.1f)
            p < 6.7f -> mix(52f, 244f, ease((p - 3.9f) / 2.8f))
            else -> 244f + sin((p - 6.7f) * 9f) * 6f * (1f - (p - 6.7f) / 1.3f)
        }

    private fun Pen.beam(beamX: Float) {
        val shown = inn(2f)
        shape {
            moveTo(CameraX - 4f, CameraY + 2f)
            lineTo(beamX - BeamHalf, Ground + 1f)
            lineTo(beamX + BeamHalf, Ground + 1f)
            close()
        }
        fill(colors.danger, 0.1f * shown)
        ell(beamX, Ground + 1f, BeamHalf, 4.5f)
        fill(colors.danger, 0.22f * shown)
        seg(CameraX - 4f, CameraY + 2f, beamX - BeamHalf, Ground + 1f, colors.danger, 0.3f * shown, 1f)
        seg(CameraX - 4f, CameraY + 2f, beamX + BeamHalf, Ground + 1f, colors.danger, 0.3f * shown, 1f)
    }

    /** Data goes from the phone to the limit, and turns back. */
    private fun Pen.limit() {
        rr(8f, Ground - 58f, 128f, 66f, 14f)
        stroke(colors.signal, 0.5f, 1.2f, dash = 2f, gap = 4f)
        repeat(4) { i ->
            val u = fract(time * 0.33f + i / 4f)
            val angle = -0.15f - i * 0.5f
            val reach = sin(u * Pi)
            val x = 34f + kotlin.math.cos(angle) * reach * if (i < 2) 94f else 52f
            val y = Ground - 22f + sin(angle) * reach * if (i < 2) 30f else 34f
            circ(x, y, 1.7f)
            fill(colors.signal, 0.9f)
            if (u > 0.46f && u < 0.56f) {
                circ(x, y, 3f + (u - 0.46f) * 40f)
                stroke(colors.signal, (0.56f - u) * 8f, 1f)
            }
        }
    }

    private fun Pen.hide(
        hidden: Hidden,
        beamX: Float,
        dt: Float,
    ) {
        val x = hidden.x + sin(time * 0.4f + hidden.id) * 8f
        val depth = (hidden.y - 14f * hidden.s - CameraY) / (Ground - CameraY)
        val away = x - mix(CameraX - 4f, beamX, depth)
        val lit = abs(away) < BeamHalf * depth + 7f
        val near = abs(away) < BeamHalf * depth + 34f
        hidden.fade.step(if (lit) 1f else 0f, 110f, 13f, dt)
        val gone = cl(hidden.fade.x)
        shadow(x, Ground + 2f, 9f * hidden.s, 0.1f * (1f - gone))
        val eye =
            when {
                gone > 0.4f -> Eye.Shut
                near -> Eye.Narrow
                else -> Eye.Open
            }
        val look =
            when {
                lit -> 0f
                near -> -sign(away)
                else -> 0.9f
            }
        ghost(
            Ghost(
                x,
                hidden.y,
                hidden.s,
                ph = gone * 0.97f,
                look = look,
                lookY = if (near) 0f else -0.4f,
                eye = eye,
                id = hidden.id,
            ),
        )
    }

    private fun Pen.camera(
        beamX: Float,
        puzzled: Boolean,
    ) {
        val aim = atan2(Ground - CameraY, beamX - CameraX)
        at(CameraX, CameraY, aim - 0.25f + if (puzzled) sin(time * 3f) * 0.05f else 0f) {
            rr(-10f, -7f, 24f, 14f, 4f)
            fill(colors.ink, 0.95f)
            rr(-16f, -4.5f, 8f, 9f, 2f)
            fill(colors.ink, 0.95f)
            circ(-16.5f, 0f, 3.4f)
            fill(colors.night)
            circ(-16.5f, 0f, if (puzzled) 1.2f else 2.2f)
            fill(colors.danger, 0.6f + 0.4f * sin(time * 6f))
            rr(6f, -10f, 6f, 3f, 1.5f)
            fill(colors.ink, 0.95f)
            circ(9f, -2f, 1.3f)
            fill(colors.danger, if (sin(time * 4f) > 0f) 1f else 0.2f)
        }
        circ(CameraX + 8f, CameraY + 6f, 3.4f)
        fill(colors.ink)
    }

    private companion object {
        const val CameraX = 262f
        const val CameraY = 42f
        const val BeamHalf = 26f
    }
}
