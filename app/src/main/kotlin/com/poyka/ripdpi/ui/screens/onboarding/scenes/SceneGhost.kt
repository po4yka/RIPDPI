// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class Eye { Open, Narrow, Shut, Crossed }

/**
 * The ghost of the app icon in one frame: two tall eyes and a sheet with a wavy hem. It floats and leans into its
 * motion, and its hem trails. It does not jump. With [ph] it turns to a dashed outline, to pass through a thing.
 *
 * @param y the hem of the sheet.
 * @param lag the direction and the share of its speed, from -1 to 1.
 * @param look where the eyes look, from -1 to 1 on each axis.
 */
internal class Ghost(
    val x: Float,
    val y: Float,
    val s: Float = 1f,
    val a: Float = 1f,
    val ph: Float = 0f,
    val lag: Float = 0f,
    val tilt: Float = 0f,
    val flip: Boolean = false,
    val look: Float = 0f,
    val lookY: Float = 0f,
    val eye: Eye = Eye.Open,
    val id: Int = 0,
    val still: Boolean = false,
    val night: Boolean = false,
)

internal fun Pen.ghost(ghost: Ghost) {
    if (ghost.a <= 0.01f) return
    val lag = cl(ghost.lag, -1f, 1f)
    // A fast ghost leaves two faint copies behind it.
    if (abs(lag) > 0.7f && !ghost.still && ghost.s > 0.7f) {
        ghostSheet(ghost, -sign(lag) * 9f * ghost.s, ghost.a * 0.07f, face = false)
        ghostSheet(ghost, -sign(lag) * 4.5f * ghost.s, ghost.a * 0.15f, face = false)
    }
    ghostSheet(ghost, 0f, ghost.a, face = true)
}

private fun Pen.ghostSheet(
    ghost: Ghost,
    dx: Float,
    a: Float,
    face: Boolean,
) {
    val lag = cl(ghost.lag, -1f, 1f)
    val speed = abs(lag)
    val ph = cl(ghost.ph)
    val bob = if (ghost.still) 0f else sin(time * 1.7f + ghost.id * 2.1f) * 1.7f
    val line = if (ghost.night) colors.night else colors.ink
    val side = if (ghost.flip) -1f else 1f
    val sx = side * ghost.s * (1f + speed * 0.05f)
    val sy = ghost.s * (1f - speed * 0.05f)
    at(ghost.x + dx, ghost.y + bob, ghost.tilt + lag * 0.14f, sx, sy) {
        if (ghost.night && face) {
            at(0f, 3f, 0f, 1.25f, 1.2f) {
                sheetShape(lag, ghost.id)
                fill(colors.glowHigh, 0.12f * a)
            }
        }
        sheetShape(lag, ghost.id)
        fill(if (ghost.night) colors.nightLight else colors.sheet, a * (1f - ph * 0.8f))
        stroke(line, a * (1f - ph * 0.25f), 1.5f / sqrt(ghost.s), dash = if (ph > 0.08f) 2.5f else 0f)
        if (face) ghostFace(ghost, a, if (ghost.night) colors.night else colors.eye, line)
    }
}

private fun Pen.sheetShape(
    lag: Float,
    id: Int,
) {
    val bx = -lag * 5.5f
    val speed = abs(lag)
    val wave = 0.7f + speed * 0.6f
    shape {
        moveTo(-9.6f, -22f)
        quadraticTo(-9.9f, -30.6f, -5.2f, -30.6f)
        quadraticTo(-1.1f, -30.6f, -1.5f, -24.6f)
        quadraticTo(0f, -22.4f, 1.5f, -24.6f)
        quadraticTo(1.1f, -30.6f, 5.2f, -30.6f)
        quadraticTo(9.9f, -30.6f, 9.6f, -22f)
        cubicTo(10.5f, -14f, 12.8f + bx * 0.5f, -9f, 12.8f + bx, -3f)
        for (k in 1..6) {
            val x = 12.8f - k * 25.6f / 6f + bx
            val x0 = 12.8f - (k - 0.5f) * 25.6f / 6f + bx
            val peak = if (k % 2 == 1) 2.8f else -1.4f
            val y0 = -3f + peak * (wave + 0.3f * sin(time * (3f + speed * 4f) + k + id))
            quadraticTo(x0, y0, x, -3f + sin(time * 2.4f + k * 1.3f + id) * 0.8f)
        }
        cubicTo(-12.8f + bx * 0.5f, -9f, -10.5f, -14f, -9.6f, -22f)
        close()
    }
}

private fun Pen.ghostFace(
    ghost: Ghost,
    a: Float,
    eyeColor: Color,
    line: Color,
) {
    val ph = cl(ghost.ph)
    if (ph > 0.3f) {
        repeat(3) { k ->
            val y = -26f + k * 8f + (time * 30f + k * 5f) % 8f
            seg(-11f, y, 11f, y, line, a * ph * 0.22f, 1f)
        }
    }
    val blink = if (fract(time * 0.19f + ghost.id * 0.37f) > 0.965f) 0.12f else 1f
    val open =
        when (ghost.eye) {
            Eye.Shut -> 0.1f
            Eye.Narrow -> 0.45f
            else -> blink
        }
    listOf(-5.2f, 5.2f).forEach { x ->
        if (ghost.eye == Eye.Crossed) {
            seg(x - 1.5f, -27f, x + 1.5f, -23f, eyeColor, a, 1.3f)
            seg(x - 1.5f, -23f, x + 1.5f, -27f, eyeColor, a, 1.3f)
        } else {
            ell(x + ghost.look * 0.9f, -25f + ghost.lookY * 0.9f, 1.55f, 3.7f * open)
            fill(eyeColor, a * (1f - ph * 0.4f))
        }
    }
}

/** A red line of a scanner across a ghost that is at ([x], [y]). [at] is how far down the sheet it is, from 0 to 1. */
internal fun Pen.scanLine(
    x: Float,
    y: Float,
    at: Float,
) {
    val lineY = y - 31f + at * 30f
    seg(x - 13f, lineY, x + 13f, lineY, colors.danger, 0.9f, 1.6f)
    rr(x - 13f, lineY - 5f, 26f, 5f, 0f)
    fill(colors.danger, 0.15f)
}

internal fun Pen.crack(
    points: FloatArray,
    count: Int,
    color: Color,
    a: Float = 0.9f,
    w: Float = 1.2f,
) {
    val shown = count.coerceIn(0, points.size / 2)
    if (shown < 2) return
    shape {
        moveTo(points[0], points[1])
        for (i in 1 until shown) lineTo(points[i * 2], points[i * 2 + 1])
    }
    stroke(color, a, w)
}

private val TombCrack = floatArrayOf(0.1f, -1f, 0.02f, -0.86f, 0.14f, -0.74f, 0.04f, -0.6f, 0.2f, -0.5f, 0.12f, -0.36f)
private val TombBranchA = floatArrayOf(0.14f, -0.74f, 0.3f, -0.7f, 0.36f, -0.62f)
private val TombBranchB = floatArrayOf(0.04f, -0.6f, -0.1f, -0.55f, -0.16f, -0.46f)

/**
 * The tombstone of the app icon, with its base at ([x], [y]).
 *
 * @param carving how much of the carving shows, from 0 to 1.
 * @param cracked how far the crack has grown, from 0 to 1.
 */
internal fun Pen.tomb(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    tilt: Float = 0f,
    carving: Float = 0f,
    cracked: Float = 0f,
    night: Boolean = false,
) {
    val stone = if (night) colors.night else colors.stone
    val carve = if (night) colors.tone(0.38f) else colors.carve
    at(x, y, tilt) {
        rr(-w / 2f, -h, w, h, w / 2f, w / 2f, 0f, 0f)
        fill(stone, 0.97f)
        rr(-w * 0.59f, -h * 0.1f, w * 1.18f, h * 0.1f, h * 0.03f)
        fill(stone)
        if (carving > 0f) {
            text("R.I.P.", 0f, -h * 0.68f, w * 0.15f, carve, carving, TextCarved)
            text("DPI", 0f, -h * 0.44f, w * 0.3f, carve, carving, TextCarved)
            rr(-w * 0.22f, -h * 0.3f, w * 0.44f, 1.2f, 0.6f)
            fill(carve, carving * 0.7f)
        }
        val k = cl(cracked)
        if (k > 0f) {
            at(0f, 0f, 0f, w, h) {
                crack(TombCrack, 1 + ceil(k * 5f).roundToInt(), carve, 0.9f, 1.4f / w)
                if (k > 0.5f) crack(TombBranchA, 3, carve, 0.9f * (k - 0.5f) * 2f, 1.1f / w)
                if (k > 0.7f) crack(TombBranchB, 3, carve, 0.9f, 1f / w)
            }
        }
    }
}
