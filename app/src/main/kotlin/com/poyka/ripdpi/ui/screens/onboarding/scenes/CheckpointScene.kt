// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

private const val Pi = PI.toFloat()
private const val Cells = 10
private const val Letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val Noise = "#%&@$?*+=<>~^"
private const val Round = 7f
private const val StampX = 140f
private const val Question = "example.org"
private const val Answer = "93.184.216.34"

// The two times in a round at which the stamp comes down: once for the question, once for the answer.
private val Slams = floatArrayOf(0.185f, 0.705f)

/**
 * The checkpoint. A split-flap board shows the resolver; a new choice flips every letter, swings the board, and
 * rings its bell. Below it the ghost carries a sealed name through a checkpoint. The stamp of the censor comes
 * down, goes through the ghost, and marks only the ground. Under the gantry the name reads as noise.
 */
internal class CheckpointScene(
    parts: Parts,
) : Scene(parts) {
    private class Flap {
        var letter = ' '
        val queue = ArrayDeque<Char>()
        var wait = 0f
        var turn = 0f
    }

    private val flaps = List(Cells) { Flap() }
    private val swing = Spring()
    private val bell = Spring()
    private val marks = ArrayList<Float>()
    private var shown: String? = null
    private var flipping = false
    private var slammed = -1
    private var shake = 0f

    override val stillAt = 3.4f

    override fun enter(frame: Frame) {
        super.enter(frame)
        shown = null
        marks.clear()
        slammed = -1
    }

    override fun Pen.draw(frame: Frame) {
        val dt = frame.dt
        if (frame.dns != shown) flipTo(frame.dns)
        swing.step(0f, 34f, 2.2f, dt)
        bell.step(0f, 60f, 2.5f, dt)
        shake *= exp(-dt * 9f)
        val p = since % Round / Round
        val round = (since / Round).toInt()
        val going = p < 0.4f
        val ghostX =
            when {
                p < 0.4f -> mix(34f, 236f, ease(p / 0.4f))
                p < 0.52f -> 236f
                p < 0.92f -> mix(236f, 34f, ease((p - 0.52f) / 0.4f))
                else -> 34f
            }
        val lookup = cl((p - 0.4f) / 0.03f) * cl((0.52f - p) / 0.03f)
        val near = exp(-((ghostX - StampX) / 26f) * ((ghostX - StampX) / 26f))
        sky(0.7f)
        at(0f, sin(time * 70f) * shake * 2.2f) {
            board(dt)
            seg(0f, Ground + 0.5f, SceneWidth, Ground + 0.5f, colors.ink, 0.5f, 1.2f)
            rise(1f) {
                shadow(24f, Ground + 1f, 11f)
                phone(24f, Ground, 20f, 36f, -0.04f, colors.night)
                circ(24f, Ground - 18f, 2.6f + if (p > 0.92f) sin((p - 0.92f) / 0.08f * Pi) * 4f else 0f)
                fill(colors.signal)
            }
            rise(4f) { cardIndex(round, lookup) }
            slam(p, round, dt)
            rise(2f) { gantry(ghostX) }
            carrier(p, ghostX, going, near)
            if (lookup > 0f) {
                repeat(3) { k ->
                    circ(250f + k * 8f, Ground - 56f, 1.6f)
                    fill(colors.signal, lookup * (0.3f + 0.7f * maxOf(0f, sin(time * 9f - k * 1.2f))))
                }
            }
            stamp(p)
        }
    }

    private fun flipTo(name: String) {
        shown = name
        // A name that is longer than the board loses its last words.
        val upper = name.uppercase()
        val fitted = if (upper.length > Cells) upper.take(Cells + 1).substringBeforeLast(' ').take(Cells) else upper
        val target = fitted.padEnd(Cells)
        flaps.forEachIndexed { i, flap ->
            flap.queue.clear()
            repeat(4 + i % 4) {
                flap.queue +=
                    Letters[parts.rnd(Letters.length.toFloat()).toInt().coerceAtMost(Letters.length - 1)]
            }
            flap.queue += target[i]
            flap.wait = -i * 0.07f
        }
        swing.v += 1.1f
        flipping = true
    }

    /** The board hangs on two chains. It comes down when the scene opens. */
    private fun Pen.board(dt: Float) {
        var busy = false
        at(150f, -8f - (1f - inn(0f)) * 50f, swing.x * 0.07f) {
            listOf(-70f, 70f).forEach { x ->
                repeat(4) { k ->
                    ell(x, 7f + k * 5f, 1.6f, 2.6f)
                    stroke(colors.ink, 0.7f, 1.1f)
                }
            }
            rr(-96f, 24f, 192f, 38f, 10f)
            fill(colors.ink, 0.95f)
            flaps.forEachIndexed { i, flap ->
                if (flap.queue.isNotEmpty()) {
                    busy = true
                    flap.wait += dt
                    if (flap.wait > 0.07f) {
                        flap.wait = 0f
                        flap.letter = flap.queue.removeFirst()
                        flap.turn = 1f
                    }
                }
                flap.turn = maxOf(0f, flap.turn - dt * 12f)
                val x = -87f + i * 17.5f
                rr(x, 30f, 15.5f, 26f, 3f)
                fill(colors.paper, 0.14f)
                at(x + 7.75f, 43.5f, 0f, 1f, 1f - flap.turn * 0.9f) {
                    text(flap.letter.toString(), 0f, 0f, 15f, colors.paper)
                }
                rr(x, 42.5f, 15.5f, 1f, 0f)
                fill(colors.ink, 0.8f)
            }
            at(92f, 62f, bell.x * 0.5f) {
                seg(0f, 0f, 0f, 5f, colors.ink, 0.8f, 1.2f)
                shape {
                    moveTo(-5.5f, 13f)
                    quadraticTo(-5f, 4f, 0f, 4f)
                    quadraticTo(5f, 4f, 5.5f, 13f)
                    close()
                }
                fill(colors.signal)
                stroke(colors.ink, 0.8f, 1.1f)
                circ(bell.x * 3f, 14f, 1.6f)
                fill(colors.ink)
            }
        }
        if (flipping && !busy) {
            flipping = false
            bell.v += 5f
            parts.ring(242f, 62f, colors.signal, 3f)
        }
    }

    /** The index of addresses. One drawer opens while the name is looked up. */
    private fun Pen.cardIndex(
        round: Int,
        lookup: Float,
    ) {
        shadow(262f, Ground + 1f, 26f)
        rr(240f, Ground - 46f, 46f, 46f, 5f)
        fill(colors.paper)
        fill(colors.ink, 0.1f)
        stroke(colors.ink, 0.85f, 1.5f)
        rr(237f, Ground - 50f, 52f, 6f, 3f)
        fill(colors.ink, 0.95f)
        repeat(4) { i ->
            val open = i == round % 4
            val out = if (open) lookup * 5f else 0f
            val x = 244f + (i % 2) * 20f - out
            val y = Ground - 40f + (i / 2) * 19f
            rr(x, y, 18f, 15f, 2.5f)
            fill(colors.paper)
            fill(colors.ink, 0.05f + if (open) lookup * 0.1f else 0f)
            stroke(colors.ink, 0.7f, 1.2f)
            rr(x + 5.5f, y + 9f, 7f, 2.4f, 1.2f)
            fill(colors.ink, 0.75f)
            rr(x + 3.5f, y + 3f, 11f, 3f, 1f)
            val found = open && lookup > 0.5f
            fill(if (found) colors.signal else colors.ink, if (found) 0.9f else 0.18f)
        }
    }

    /** How far down the stamp is, from -0.3 at the top of its wind-up to 1 on the ground. */
    private fun stampDown(p: Float): Float {
        var down = 0f
        Slams.forEach { at ->
            val u = (p - at) * Round
            val one =
                when {
                    u < -0.6f -> 0f
                    u < 0f -> -sin((u + 0.6f) / 0.6f * Pi / 2f) * 0.3f
                    u < 0.08f -> -0.3f + u / 0.08f * 1.3f
                    u < 0.7f -> 1f
                    u < 1.3f -> 1f - ease((u - 0.7f) / 0.6f)
                    else -> 0f
                }
            if (kotlin.math.abs(one) > kotlin.math.abs(down)) down = one
        }
        return down
    }

    /** The stamp lands: the ground shakes, and its mark stays for a time. */
    private fun Pen.slam(
        p: Float,
        round: Int,
        dt: Float,
    ) {
        Slams.forEachIndexed { i, at ->
            val key = round * 2 + i
            if (p > at + 0.012f && p < at + 0.1f && slammed != key) {
                slammed = key
                shake = 1f
                parts.dust(StampX - 14f, Ground, colors.mid)
                parts.dust(StampX + 14f, Ground, colors.mid)
                parts.ring(StampX, Ground - 2f, colors.danger, 6f)
                marks += 0f
            }
        }
        for (i in marks.indices) marks[i] += dt
        marks.removeAll { it > 3f }
        marks.forEach { age ->
            val a = cl(1.4f - age / 2f) * 0.75f * cl(age * 3f - 0.6f)
            at(StampX, Ground + 7f, -0.1f, 1f, 0.42f) {
                rr(-24f, -8f, 48f, 16f, 3f)
                stroke(colors.danger, a, 2.2f)
                circ(0f, 0f, 4.5f)
                stroke(colors.danger, a, 2f)
                seg(-3.2f, 3.2f, 3.2f, -3.2f, colors.danger, a, 2f)
            }
        }
    }

    private fun Pen.gantry(ghostX: Float) {
        val alert = cl(1f - kotlin.math.abs(ghostX - StampX) / 70f)
        seg(StampX - 30f, Ground, StampX - 30f, Ground - 84f, colors.ink, 0.95f, 4f)
        seg(StampX + 30f, Ground, StampX + 30f, Ground - 84f, colors.ink, 0.95f, 4f)
        rr(StampX - 36f, Ground - 90f, 72f, 9f, 3f)
        fill(colors.ink, 0.95f)
        listOf(-30f, 30f).forEach { side ->
            rr(StampX + side - 6f, Ground - 3f, 12f, 3f, 1.5f)
            fill(colors.ink, 0.95f)
        }
        circ(StampX, Ground - 85.5f, 3f + alert * 1.5f)
        fill(colors.danger, if (alert > 0.1f) 1f else 0.4f + 0.3f * sin(time * 3f))
        if (alert > 0.1f) {
            val u = fract(time * 2f)
            circ(StampX, Ground - 85.5f, 5f + u * 8f)
            stroke(colors.danger, (1f - u) * alert, 1.2f)
        }
    }

    /** The ghost with the card that it carries. The card is sealed: near the stamp its text is noise. */
    private fun Pen.carrier(
        p: Float,
        ghostX: Float,
        going: Boolean,
        near: Float,
    ) {
        val alert = cl(1f - kotlin.math.abs(ghostX - StampX) / 70f)
        val returns = p > 0.52f && p < 0.92f
        val moves = going || returns
        val along = if (going) p / 0.4f else (p - 0.52f) / 0.4f
        val lag = if (moves) (if (going) 0.8f else -0.8f) * sin(along * Pi) else 0f
        shadow(ghostX, Ground + 2f, 10f * (1f - near), 0.1f * (1f - near))
        ghost(
            Ghost(
                x = ghostX,
                y = Ground - 7f,
                ph = near * 0.95f,
                lag = lag,
                look = if (p < 0.52f) 1f else -1f,
                eye = if (near > 0.4f || alert > 0.5f) Eye.Narrow else Eye.Open,
                id = 5,
            ),
        )
        val missed = Slams.any { (p - it) * Round > 0.25f && (p - it) * Round < 1.2f }
        if (missed) text("?", StampX + 24f, Ground - 98f, 12f, colors.ink, 0.9f, TextCarved)
        if (p >= 0.92f) return
        val plain = if (p > 0.52f) Answer else Question
        val shownText =
            if (near > 0.25f) {
                val shift = (time * 14f).toInt()
                String(CharArray(plain.length) { Noise[(shift + it * 7) % Noise.length] })
            } else {
                plain
            }
        val y = Ground - 48f + sin(time * 1.7f + 10.5f) * 1.7f
        val color = if (p > 0.52f) colors.signal else colors.ink
        card(
            ghostX + if (going) -2f else 2f,
            y,
            shownText,
            color,
            -0.06f,
            0.72f,
            (1f - near * 0.5f) * cl((0.92f - p) / 0.03f),
        )
    }

    private fun Pen.stamp(p: Float) {
        val down = stampDown(p)
        val y = Ground - 66f + cl(down, -0.3f, 1f) * 37f
        val squash = if (down >= 0.999f) 0.82f else 1f
        seg(StampX, Ground - 82f, StampX, y - 12f, colors.ink, 0.9f, 3.5f)
        rr(StampX - 7f, y - 14f, 14f, 7f, 2f)
        fill(colors.ink, 0.95f)
        at(StampX, y + 5f, 0f, 2f - squash, squash) {
            shape {
                moveTo(-9f, -12f)
                lineTo(9f, -12f)
                lineTo(19f, -4f)
                lineTo(-19f, -4f)
                close()
            }
            fill(colors.ink, 0.95f)
            rr(-20f, -5f, 40f, 5f, 1.5f)
            fill(colors.danger)
        }
    }
}
