// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val Pi = PI.toFloat()

internal fun Pen.shadow(
    x: Float,
    y: Float,
    rx: Float,
    a: Float = 0.1f,
) {
    ell(x, y, rx, rx * 0.2f)
    fill(colors.ink, a)
}

/** A star with four points. */
internal fun Pen.star(
    x: Float,
    y: Float,
    r: Float,
    rot: Float = 0f,
) {
    shape {
        repeat(8) { k ->
            val reach = if (k % 2 == 1) r * 0.34f else r
            val angle = rot + k * Pi / 4f
            val px = x + cos(angle) * reach
            val py = y + sin(angle) * reach
            if (k == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

private val CloudPuffs = floatArrayOf(0f, 0f, 9f, -10f, 3f, 6.5f, 10f, 3f, 7f, 4f, -5f, 6.5f, -4f, 5f, 7f, 5f, 5f, 7f)

internal fun Pen.cloud(
    x: Float,
    y: Float,
    s: Float,
    color: Color,
    a: Float,
) {
    repeat(CloudPuffs.size / 3) { i ->
        circ(x + CloudPuffs[i * 3] * s, y + CloudPuffs[i * 3 + 1] * s, CloudPuffs[i * 3 + 2] * s)
        fill(color, a)
    }
}

internal fun Pen.hill(
    base: Float,
    amp: Float,
    length: Float,
    phase: Float,
    color: Color,
    a: Float,
) {
    shape {
        moveTo(0f, SceneHeight)
        var x = 0f
        while (x <= SceneWidth) {
            lineTo(x, base - amp * sin(x / length + phase) - amp * 0.4f * sin(x / length * 2.3f + phase * 2f))
            x += 6f
        }
        lineTo(SceneWidth, SceneHeight)
        close()
    }
    fill(color, a)
}

/** The backdrop of the day scenes: two hills, three clouds that drift, and the line of the ground. */
internal fun Pen.sky(a: Float = 1f) {
    hill(Ground - 8f, 9f, 46f, 1f + time * 0.02f, colors.ink, 0.045f * a)
    hill(Ground - 2f, 6f, 31f, 3f, colors.ink, 0.06f * a)
    cloud((40f + time * 3f) % 340f - 20f, 26f, 1f, colors.ink, 0.07f * a)
    cloud((160f + time * 4f) % 340f - 20f, 16f, 0.8f, colors.ink, 0.07f * a)
    cloud((262f + time * 5f) % 340f - 20f, 34f, 0.9f, colors.ink, 0.07f * a)
    seg(0f, Ground + 0.5f, SceneWidth, Ground + 0.5f, colors.ink, 0.16f * a, 1f)
}

/** A globe on a stand: the open internet. It turns, and it swells with [pulse]. */
internal fun Pen.globe(
    x: Float,
    y: Float,
    r: Float,
    pulse: Float = 0f,
    lit: Float = 1f,
) {
    shadow(x, y + r + 12f, r * 0.95f)
    rr(x - 2.5f, y + r - 1f, 5f, 10f, 2f)
    fill(colors.ink, 0.88f)
    rr(x - 10f, y + r + 8f, 20f, 4f, 2f)
    fill(colors.ink, 0.88f)
    val q = r * (1f + pulse * 0.1f)
    circ(x, y, q)
    fill(colors.paper)
    fill(colors.signal, 0.1f + 0.12f * lit + pulse * 0.25f)
    clipped {
        repeat(3) { k ->
            ell(x, y, q * abs(cos(time * 0.45f + k * 1.05f)), q)
            stroke(colors.ink, 0.28f, 1f)
        }
        listOf(-0.5f, 0f, 0.5f).forEach { d ->
            shape {
                moveTo(x - q, y + d * q)
                quadraticTo(x, y + d * q + 4f, x + q, y + d * q)
            }
            stroke(colors.ink, 0.28f, 1f)
        }
    }
    circ(x, y, q)
    stroke(colors.ink, 0.9f, 1.6f)
    val ringX = x + (q + 4.5f) * cos(-0.5f)
    val ringY = y + (q + 4.5f) * sin(-0.5f)
    shape {
        moveTo(ringX, ringY)
        repeat(9) { k ->
            val angle = -0.5f + (k + 1) * 0.18f
            lineTo(x + (q + 4.5f) * cos(angle), y + (q + 4.5f) * sin(angle))
        }
    }
    stroke(colors.ink, 0.45f, 1.3f)
    circ(ringX, ringY, 1.6f)
    fill(colors.ink, 0.7f)
}

/** A phone that stands on ([x], [y]). */
internal fun Pen.phone(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    rot: Float,
    screen: Color,
    body: Color = colors.ink,
    a: Float = 1f,
) {
    at(x, y, rot) {
        rr(-w / 2f, -h, w, h, w * 0.22f)
        fill(body, 0.92f * a)
        rr(-w / 2f + 2.5f, -h + 3f, w - 5f, h - 8f, w * 0.14f)
        fill(screen, a)
    }
}

internal fun Pen.grass(
    x: Float,
    y: Float,
    blades: Int,
    color: Color = colors.ink,
    a: Float = 0.55f,
) {
    repeat(blades) { i ->
        val sway = sin(time * 1.3f + x + i) * 1.6f
        val lean = i - blades / 2f
        shape {
            moveTo(x + i * 3f, y)
            quadraticTo(x + i * 3f + lean, y - 5f, x + i * 3f + lean * 1.6f + sway, y - 7f - (i % 2) * 3f)
        }
        stroke(color, a, 1.1f)
    }
}

// Each branch of the bare tree: from, to, and the width.
private val TreeBranches =
    floatArrayOf(
        0f,
        0f,
        -2f,
        -34f,
        5f,
        -2f,
        -34f,
        -16f,
        -56f,
        3f,
        -2f,
        -34f,
        10f,
        -58f,
        3f,
        -9f,
        -45f,
        -22f,
        -46f,
        1.8f,
        -16f,
        -56f,
        -14f,
        -68f,
        1.5f,
        -16f,
        -56f,
        -26f,
        -62f,
        1.5f,
        4f,
        -46f,
        18f,
        -44f,
        1.8f,
        10f,
        -58f,
        20f,
        -66f,
        1.5f,
        10f,
        -58f,
        6f,
        -70f,
        1.5f,
        -1f,
        -18f,
        10f,
        -26f,
        2f,
    )

internal fun Pen.tree(
    x: Float,
    y: Float,
    s: Float,
    color: Color,
    a: Float,
) {
    repeat(TreeBranches.size / 5) { i ->
        val x0 = TreeBranches[i * 5]
        val y0 = TreeBranches[i * 5 + 1]
        val x1 = TreeBranches[i * 5 + 2]
        val y1 = TreeBranches[i * 5 + 3]
        shape {
            moveTo(x + x0 * s, y + y0 * s)
            quadraticTo(x + (x0 + x1) / 2f * s + 2f * s, y + (y0 + y1) / 2f * s, x + x1 * s, y + y1 * s)
        }
        stroke(color, a, TreeBranches[i * 5 + 4] * s)
    }
}

/** An iron fence along [y]. */
internal fun Pen.fence(
    y: Float,
    a: Float,
    color: Color = colors.ink,
) {
    var x = 6f
    while (x < SceneWidth) {
        seg(x, y, x, y - 15f, color, a, 1.4f)
        val px = x
        shape {
            moveTo(px - 2.2f, y - 15f)
            lineTo(px, y - 19.5f)
            lineTo(px + 2.2f, y - 15f)
            close()
        }
        fill(color, a)
        x += 13f
    }
    seg(0f, y - 4f, SceneWidth, y - 4f, color, a, 1.2f)
    seg(0f, y - 12f, SceneWidth, y - 12f, color, a, 1.2f)
}

/** A crow that sits on ([x], [y]). [head] turns its head, from -1 to 1. */
internal fun Pen.crow(
    x: Float,
    y: Float,
    head: Float,
) {
    at(x, y) {
        ell(0f, -5f, 6.5f, 4.2f, -0.25f)
        fill(colors.ink)
        shape {
            moveTo(4f, -4f)
            lineTo(11f, -1.5f)
            lineTo(4f, -2f)
            close()
        }
        fill(colors.ink)
        at(-4.5f, -8.5f, head * 0.5f) {
            circ(0f, 0f, 3.1f)
            fill(colors.ink)
            shape {
                moveTo(-2.5f, -0.8f)
                lineTo(-7f, 0.6f)
                lineTo(-2.5f, 1.3f)
                close()
            }
            fill(colors.ink)
            circ(-0.9f, -0.6f, 0.8f)
            fill(colors.paper)
        }
    }
    seg(x - 1f, y - 1.5f, x - 1f, y + 1f, colors.ink, 1f, 1f)
    seg(x + 1.5f, y - 1.5f, x + 1.5f, y + 1f, colors.ink, 1f, 1f)
}

private val WallCrack = floatArrayOf(0.5f, 0f, 0.3f, 0.2f, 0.6f, 0.36f, 0.35f, 0.55f)

/** A brick wall with wire on its top, from [top] down to the ground. */
internal fun Pen.wall(
    x: Float,
    top: Float,
    w: Float,
    cracked: Float = 0f,
) {
    val h = Ground - top
    rr(x, top, w, h, 2f)
    fill(colors.paper)
    fill(colors.ink, 0.2f)
    stroke(colors.ink, 0.9f, 1.5f)
    var row = 0
    while (row * 8f < h - 3f) {
        val y = top + row * 8f
        if (row > 0) seg(x + 1f, y, x + w - 1f, y, colors.ink, 0.45f, 1f)
        var k = if (row % 2 == 1) w / 4f else w / 2f
        while (k < w) {
            seg(x + k, y + 1f, x + k, minOf(Ground, y + 8f) - 1f, colors.ink, 0.45f, 1f)
            k += w / 2f
        }
        row++
    }
    rr(x - 2f, top - 3f, w + 4f, 4f, 1.5f)
    fill(colors.ink, 0.95f)
    repeat(4) { k ->
        ell(x + 2f + k * (w - 4f) / 3f, top - 7f, 3.2f, 4f, 0.5f)
        stroke(colors.ink, 0.75f, 1f)
    }
    val k = cl(cracked)
    if (k > 0f) {
        at(x, top, 0f, w, h) { crack(WallCrack, 2 + (k * 2f).toInt(), colors.ink, 0.9f, 1.5f / w) }
    }
}

/** A small light that drifts. */
internal fun Pen.wisp(
    x: Float,
    y: Float,
    r: Float,
    a: Float,
) {
    circ(x, y, r * 2.4f)
    fill(colors.glow, a * 0.18f)
    circ(x, y, r)
    fill(colors.glow, a)
}

/** A dial with a pointer at [angle] radians. With [ticks] it has a scale around it, lit up to the pointer. */
internal fun Pen.knob(
    x: Float,
    y: Float,
    r: Float,
    angle: Float,
    a: Float,
    ticks: Boolean = false,
) {
    if (a <= 0.01f || r < 0.5f) return
    if (ticks) {
        repeat(11) { k ->
            val at = -2.2f + k * 0.44f
            val lit = k <= 5f + sin(angle) * 5f
            seg(
                x + sin(at) * (r + 3.5f),
                y - cos(at) * (r + 3.5f),
                x + sin(at) * (r + 6.5f),
                y - cos(at) * (r + 6.5f),
                colors.ink,
                a * if (lit) 0.8f else 0.25f,
                1.3f,
            )
        }
    }
    circ(x + 0.8f, y + 1.4f, r)
    fill(colors.ink, a * 0.2f)
    circ(x, y, r)
    fill(colors.ink, a * 0.92f)
    seg(
        x + sin(angle) * r * 0.3f,
        y - cos(angle) * r * 0.3f,
        x + sin(angle) * r * 0.78f,
        y - cos(angle) * r * 0.78f,
        colors.paper,
        a,
        maxOf(1.2f, r * 0.15f),
    )
}

/** A card with a lock and one line of text, with its middle at ([x], [y]). */
internal fun Pen.card(
    x: Float,
    y: Float,
    label: String,
    color: Color,
    rot: Float,
    scale: Float = 1f,
    a: Float = 1f,
) {
    if (a <= 0.01f || scale <= 0.02f) return
    val w = textWidth(label, 9f) + 20f
    at(x, y, rot, scale, scale) {
        rr(-w / 2f, -8.5f, w, 17f, 5f)
        fill(colors.paper, a)
        stroke(color, a, 1.4f)
        rr(-w / 2f + 4.5f, -2f, 6f, 5.5f, 1.3f)
        fill(color, a)
        shape {
            moveTo(-w / 2f + 5.4f, -2.2f)
            cubicTo(-w / 2f + 5.4f, -5f, -w / 2f + 9.6f, -5f, -w / 2f + 9.6f, -2.2f)
        }
        stroke(color, a, 1.2f)
        text(label, 7f, 0.5f, 9f, color, a)
    }
}
