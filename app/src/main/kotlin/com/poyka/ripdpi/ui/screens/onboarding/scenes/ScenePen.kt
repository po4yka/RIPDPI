// The numbers in this package are the coordinates and the timings of drawings, in a 300 by 170 design frame.
@file:Suppress("MagicNumber")

package com.poyka.ripdpi.ui.screens.onboarding.scenes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.poyka.ripdpi.ui.theme.RipDpiExtendedColors

/** The design frame of every scene. A scene draws in these units; the stage scales the frame to its width. */
internal const val SceneWidth = 300f
internal const val SceneHeight = 170f

/** The line of the ground in the design frame. */
internal const val Ground = 142f

/** Text is laid out once at this size in pixels, and drawn at the size that a scene asks for. */
internal const val SceneTextBase = 40f

internal const val TextMono = 0
internal const val TextCarved = 1

private const val MinAlpha = 0.01f
private const val Degrees = 57.29578f
private const val ClipDepth = 4

internal fun cl(
    value: Float,
    from: Float = 0f,
    to: Float = 1f,
): Float = value.coerceIn(from, to)

/** A smooth step from 0 to 1. */
internal fun ease(t: Float): Float {
    val u = cl(t)
    return u * u * (3f - 2f * u)
}

/** A step from 0 to 1 that passes 1 and comes back. */
internal fun back(t: Float): Float {
    val u = cl(t) - 1f
    return u * u * (2.7f * u + 1.7f) + 1f
}

internal fun mix(
    from: Float,
    to: Float,
    t: Float,
): Float = from + (to - from) * t

internal fun fract(value: Float): Float = value - kotlin.math.floor(value)

/** A damped spring on one value. */
internal class Spring(
    var x: Float = 0f,
    var v: Float = 0f,
) {
    fun step(
        target: Float,
        stiffness: Float,
        damping: Float,
        dt: Float,
    ) {
        v += (stiffness * (target - x) - damping * v) * dt
        x += v * dt
    }
}

/** The colors of the scenes. Every one is a role of the theme, or a mix of two roles. */
internal class SceneColors(
    colors: RipDpiExtendedColors,
) {
    val ink = colors.foreground
    val paper = colors.background
    val signal = colors.signal
    val danger = colors.destructive
    val mid = colors.mutedForeground
    private val dark = paper.luminance() < ink.luminance()

    /** The ghost is the light shape of the app icon in both themes. */
    val sheet = if (dark) ink else paper
    val eye = if (dark) paper else ink

    /** The tombstone is the dark shape of the app icon. On a dark page it is a step above the page. */
    val stone = if (dark) lerp(paper, ink, 0.3f) else ink
    val carve = if (dark) ink else paper

    /** The night scene has its own sky, which is the dark end of the theme. */
    val night = if (dark) paper else ink
    val nightLight = if (dark) ink else paper
    val glow = lerp(signal, nightLight, 0.45f)
    val glowHigh = lerp(signal, nightLight, 0.75f)

    /** A tone of the night between its sky and its light. */
    fun tone(share: Float): Color = lerp(night, nightLight, share)
}

/** What a scene follows in one frame: the time, and the state of the setup. */
internal class Frame {
    var dt = 0f
    var advanced = false
    var proxy = false
    var dns = ""

    /** The connection test: how many stages are complete, and where it failed, or -1. */
    var stage = 0
    var running = false
    var failedAt = -1
    var passed = false
}

/**
 * Draws shapes in the design frame. A shape is built first, and then filled or stroked, any number of times.
 * With [mute] nothing is drawn: the stage uses it to move a scene to its still frame.
 */
internal class Pen(
    val colors: SceneColors,
    private val measurer: TextMeasurer,
    private val styles: List<TextStyle>,
) {
    lateinit var scope: DrawScope
    var alpha = 1f
    var mute = false

    /** The time of the stage in seconds. Idle motion follows it. */
    var time = 0f
    val path = Path()
    private var turn = 0f
    private var turnAt = Offset.Zero
    private val clips = Array(ClipDepth) { Path() }
    private var depth = 0
    private val layouts = HashMap<String, TextLayoutResult>()

    inline fun shape(build: Path.() -> Unit) {
        path.rewind()
        resetTurn()
        path.build()
    }

    fun resetTurn() {
        turn = 0f
    }

    fun rr(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float,
    ) = rr(x, y, w, h, r, r, r, r)

    fun rr(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
    ) {
        path.rewind()
        turn = 0f
        if (w <= 0f || h <= 0f) return
        path.addRoundRect(
            RoundRect(
                rect = Rect(x, y, x + w, y + h),
                topLeft = CornerRadius(topLeft),
                topRight = CornerRadius(topRight),
                bottomRight = CornerRadius(bottomRight),
                bottomLeft = CornerRadius(bottomLeft),
            ),
        )
    }

    fun circ(
        x: Float,
        y: Float,
        r: Float,
    ) = ell(x, y, r, r)

    /** An ellipse, turned by [rot] radians about its center. */
    fun ell(
        x: Float,
        y: Float,
        rx: Float,
        ry: Float,
        rot: Float = 0f,
    ) {
        path.rewind()
        turn = rot * Degrees
        turnAt = Offset(x, y)
        if (rx <= 0f || ry <= 0f) return
        path.addOval(Rect(x - rx, y - ry, x + rx, y + ry))
    }

    /** An open arc of a circle, from [from] to [to] radians. */
    fun arc(
        x: Float,
        y: Float,
        r: Float,
        from: Float,
        to: Float,
    ) {
        path.rewind()
        turn = 0f
        if (r <= 0f) return
        path.arcTo(Rect(x - r, y - r, x + r, y + r), from * Degrees, (to - from) * Degrees, forceMoveTo = true)
    }

    fun fill(
        color: Color,
        a: Float = 1f,
    ) {
        val shown = cl(a) * alpha
        if (mute || shown <= MinAlpha) return
        if (turn != 0f) {
            scope.rotate(turn, turnAt) { drawPath(path, color, shown) }
        } else {
            scope.drawPath(path, color, shown)
        }
    }

    fun stroke(
        color: Color,
        a: Float = 1f,
        w: Float = 1.4f,
        dash: Float = 0f,
        gap: Float = dash,
        phase: Float = 0f,
    ) {
        val shown = cl(a) * alpha
        if (mute || shown <= MinAlpha) return
        val effect = if (dash > 0f) PathEffect.dashPathEffect(floatArrayOf(dash, gap), phase) else null
        val style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
        if (turn != 0f) {
            scope.rotate(turn, turnAt) { drawPath(path, color, shown, style) }
        } else {
            scope.drawPath(path, color, shown, style)
        }
    }

    fun seg(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Color,
        a: Float = 0.5f,
        w: Float = 1.2f,
    ) {
        val shown = cl(a) * alpha
        if (mute || shown <= MinAlpha) return
        scope.drawLine(color, Offset(x0, y0), Offset(x1, y1), w, StrokeCap.Round, alpha = shown)
    }

    /** Draws [block] moved to a point, turned by [rot] radians, and scaled, in that order. */
    inline fun at(
        x: Float,
        y: Float,
        rot: Float = 0f,
        sx: Float = 1f,
        sy: Float = 1f,
        block: () -> Unit,
    ) {
        scope.withTransform({
            translate(x, y)
            if (rot != 0f) rotate(rot * DegreesOfRadian, Offset.Zero)
            if (sx != 1f || sy != 1f) scale(sx, sy, Offset.Zero)
        }) { block() }
    }

    /** Draws [block] with its opacity multiplied by [share]. */
    inline fun faded(
        share: Float,
        block: () -> Unit,
    ) {
        val before = alpha
        alpha = before * cl(share)
        block()
        alpha = before
    }

    /** Draws [block] inside the shape that was built last. */
    fun clipped(block: () -> Unit) {
        val clip = clips[depth]
        clip.rewind()
        clip.addPath(path)
        depth++
        scope.clipPath(clip) { block() }
        depth--
    }

    /** Draws [text] with its middle at a point. [size] is the height of the font in the design frame. */
    fun text(
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Color,
        a: Float = 1f,
        style: Int = TextMono,
    ) {
        val shown = cl(a) * alpha
        if (mute || shown <= MinAlpha || text.isEmpty()) return
        val layout = layout(text, style)
        val k = size / SceneTextBase
        scope.withTransform({
            translate(x, y)
            scale(k, k, Offset.Zero)
        }) {
            drawText(layout, color, Offset(-layout.size.width / 2f, -layout.size.height / 2f), shown)
        }
    }

    /** The width of [text] at [size], in the design frame. */
    fun textWidth(
        text: String,
        size: Float,
        style: Int = TextMono,
    ): Float = layout(text, style).size.width * size / SceneTextBase

    private fun layout(
        text: String,
        style: Int,
    ): TextLayoutResult =
        layouts.getOrPut("$style$text") { measurer.measure(text, styles[style], softWrap = false, maxLines = 1) }
}

/** Degrees in one radian, for the inline transform of [Pen]. */
internal const val DegreesOfRadian = 57.29578f
