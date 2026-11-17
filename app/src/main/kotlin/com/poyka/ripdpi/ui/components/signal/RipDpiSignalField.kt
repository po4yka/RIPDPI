package com.poyka.ripdpi.ui.components.signal

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** The shape of the signal line. The caller picks the color; the line never carries state alone. */
enum class RipDpiSignalMode {
    /** No path: a faint flat line that fades out at both ends. */
    Idle,

    /** A path is being set up: a pulse travels along a flat line. */
    Seeking,

    /** A path is live: a steady flowing wave. */
    Live,

    /** A path is live with a warning: an irregular wave. */
    Unstable,

    /** The path failed: the wave stops at [RipDpiSignalField]'s `breakAt` and a dim flat line follows. */
    Broken,
}

/**
 * A standing ripple on a line that has no path yet: a still sine of [cycles] periods across the width, shown from
 * [start] to [end] as fractions of the width. It does not move with time and is not a connection state, so the
 * caller keeps the neutral color. Every value is read in the draw phase.
 */
@Stable
class RipDpiSignalRipple(
    val amplitude: () -> Float,
    val cycles: () -> Float,
    val start: () -> Float = { 0f },
    val end: () -> Float = { 1f },
)

internal data class SignalShape(
    val amplitude: Float,
    val pulse: Float,
    val noise: Float,
    val glow: Float,
    val broken: Boolean,
    val rest: Float = 0f,
)

internal fun RipDpiSignalMode.shape(): SignalShape =
    when (this) {
        RipDpiSignalMode.Idle -> SignalShape(0f, 0f, 0f, 0f, broken = false, rest = 1f)
        RipDpiSignalMode.Seeking -> SignalShape(0f, 1f, 0f, 1f, broken = false)
        RipDpiSignalMode.Live -> SignalShape(LiveAmplitude, 0f, 0f, 1f, broken = false)
        RipDpiSignalMode.Unstable -> SignalShape(LiveAmplitude, 0f, UnstableNoise, 1f, broken = false)
        RipDpiSignalMode.Broken -> SignalShape(LiveAmplitude, 0f, 0f, BrokenGlow, broken = true)
    }

/** The ripple of one frame. [NoRipple] adds nothing to the line. */
internal class SignalRippleFrame(
    val amplitude: Float,
    val cycles: Float,
    val start: Float,
    val end: Float,
)

private val NoRipple = SignalRippleFrame(amplitude = 0f, cycles = 0f, start = 0f, end = 1f)

/** One frame of the line. Plain floats, so the draw phase reads them without a recomposition. */
internal class SignalFrame(
    val time: Float,
    val amplitude: Float,
    val pulse: Float,
    val noise: Float,
    val cut: Float,
    val glow: Float,
    val pressAt: Float,
    val press: Float,
    /** How far the line is at rest, from 0 to 1. A resting line is faint and fades out at both ends. */
    val rest: Float = 0f,
    val ripple: SignalRippleFrame = NoRipple,
    /** How far the wave reaches from the start of the line, as a fraction of the width. */
    val front: Float = FrontOpen,
    /** The recoil of the end of a line that has just broken, from 1 at the break to 0 at rest. */
    val kick: Float = 0f,
)

/**
 * The hero signal line. An AGSL shader draws the moving line on API 33 and later. A path draws the same curve below
 * that, and draws the still frame that each [mode] shows with animations off or reduced motion. Animated values
 * are read in the draw phase only. The line is decoration and has no semantics; the caller names the state.
 *
 * @param breakAt where the line stops in [RipDpiSignalMode.Broken], as a fraction of the width.
 * @param pressAt where the pointer is, as a fraction of the width.
 * @param press how far the line dips at [pressAt], from 0 to 1.
 * @param ripple a standing ripple for a line with no path. The default draws the line as before.
 */
@Composable
fun RipDpiSignalField(
    mode: RipDpiSignalMode,
    color: Color,
    modifier: Modifier = Modifier,
    breakAt: Float = Middle,
    pressAt: () -> Float = { Middle },
    press: () -> Float = { 0f },
    ripple: RipDpiSignalRipple? = null,
) {
    val motion = RipDpiThemeTokens.motion
    val transition = updateTransition(targetState = mode, label = "Signal mode")
    val amplitude by transition.animateFloat({ motion.emphasizedTween() }, "amplitude") { it.shape().amplitude }
    val pulse by transition.animateFloat({ motion.emphasizedTween() }, "pulse") { it.shape().pulse }
    val noise by transition.animateFloat({ motion.emphasizedTween() }, "noise") { it.shape().noise }
    val glow by transition.animateFloat({ motion.emphasizedTween() }, "glow") { it.shape().glow }
    val rest by transition.animateFloat({ motion.emphasizedTween() }, "rest") { it.shape().rest }
    val cut by transition.animateFloat({ motion.emphasizedTween() }, "cut") {
        if (it.shape().broken) breakAt.coerceIn(0f, 1f) else NoCut
    }
    // The wave unrolls from the device end when a path goes live, and drains back to it when the path ends.
    val front by transition.animateFloat({ motion.emphasizedTween(easing = LinearEasing) }, "front") {
        if (it.shape().amplitude > 0f) FrontOpen else 0f
    }
    val kick = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        // A line that is already broken when it appears stays still.
        if (mode == RipDpiSignalMode.Broken && transition.currentState != RipDpiSignalMode.Broken) {
            kick.snapTo(1f)
            kick.animateTo(0f, motion.emphasizedTween())
        } else {
            kick.snapTo(0f)
        }
    }
    val lineColor by animateColorAsState(color, motion.emphasizedTween(), label = "Signal color")

    val time = remember { mutableFloatStateOf(StillTime) }
    val moving = motion.allowsInfiniteMotion && (mode != RipDpiSignalMode.Idle && mode != RipDpiSignalMode.Broken)
    if (moving) {
        LaunchedEffect(Unit) {
            // The infinite-animation clock lets a test or a host policy stop the loop, as for any infinite transition.
            // Continue from the last frame, so a restart after an idle or failed state does not jump the phase.
            // A flat line has no phase to keep, so its first pulse leaves from the pointer.
            val fromPointer = mode == RipDpiSignalMode.Seeking && amplitude == 0f
            val base = if (fromPointer) pressAt().coerceIn(0f, 1f) / PulseSpeed else time.floatValue
            val start = withInfiniteAnimationFrameNanos { it }
            while (true) {
                withInfiniteAnimationFrameNanos {
                    time.floatValue = ((it - start) / NanosPerSecond + base) % TimePeriod
                }
            }
        }
    }

    // A still frame needs no shader: the path draws it on every API level, in previews, and in host screenshot
    // tests, where the native renderer drops a runtime shader and everything drawn after it.
    val moves = motion.allowsInfiniteMotion
    val shader = remember(moves) { if (moves) signalShaderOrNull() else null }
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RipDpiSignalFieldHeight)
                .clipToBounds()
                .drawBehind {
                    val rippleFrame =
                        ripple?.let { SignalRippleFrame(it.amplitude(), it.cycles(), it.start(), it.end()) }
                            ?: NoRipple
                    val now = time.floatValue
                    // A rippled line is content, not a resting hero line, so it keeps its full strength.
                    val resting = if (ripple == null) rest else 0f
                    val frame =
                        SignalFrame(
                            now,
                            amplitude,
                            pulse,
                            noise,
                            cut,
                            glow,
                            pressAt(),
                            press(),
                            resting,
                            rippleFrame,
                            front,
                            kick.value,
                        )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                        drawRect(shader.brush(size, frame, lineColor, StrokeWidth.toPx(), GlowSpread.toPx()))
                    } else {
                        drawSignalPath(frame, lineColor)
                    }
                },
    )
}

private fun signalShaderOrNull(): SignalShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            SignalShader()
        } catch (_: IllegalArgumentException) {
            // The shader did not compile on this device; the path draws the line.
            null
        }
    } else {
        null
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class SignalShader {
    private val shader = RuntimeShader(SignalShaderSource)
    private val brush = ShaderBrush(shader)

    fun brush(
        size: Size,
        frame: SignalFrame,
        color: Color,
        strokePx: Float,
        spreadPx: Float,
    ): ShaderBrush {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", frame.time)
        shader.setFloatUniform("amp", frame.amplitude)
        shader.setFloatUniform("pulse", frame.pulse)
        shader.setFloatUniform("noise", frame.noise)
        shader.setFloatUniform("cut", frame.cut)
        shader.setFloatUniform("glow", frame.glow)
        shader.setFloatUniform("press", frame.pressAt, frame.press)
        val ripple = frame.ripple
        shader.setFloatUniform("ripple", ripple.amplitude, ripple.cycles, ripple.start, ripple.end)
        shader.setFloatUniform("rest", frame.rest)
        shader.setFloatUniform("front", frame.front)
        shader.setFloatUniform("kick", frame.kick)
        shader.setFloatUniform("stroke", strokePx / 2f)
        shader.setFloatUniform("spread", spreadPx)
        shader.setColorUniform("color", color.toArgb())
        return brush
    }
}

/** Vertical offset of the line at [u], as a fraction of the height. Mirrors `offsetAt` in the shader. */
internal fun SignalFrame.offsetAt(u: Float): Float {
    val edge = smoothStep(0f, EdgeFade, u) * (1f - smoothStep(1f - EdgeFade, 1f, u))
    var wave =
        SlowWeight * sin(Tau * (SlowCycles * u - SlowSpeed * time)) +
            FastWeight * sin(Tau * (FastCycles * u - FastSpeed * time) + FastPhase)
    wave += noise * sin(Tau * (NoiseCycles * u + NoiseSpeed * time)) * sin(Tau * (GustCycles * u - GustSpeed * time))
    val head = PulseSpeed * time
    val p = (u - (head - floor(head))) / PulseWidth
    val bump = pulse * PulseHeight * exp(-p * p) * sin(Tau * Middle * p)
    val d = (u - pressAt) / PressWidth
    val live = if (u <= cut) 1f else 0f
    val unrolled = 1f - smoothStep(front - FrontSoft, front, u)
    val whip = kick * KickHeight * exp((u - cut) / KickWidth) * sin(Tau * KickCycles * kick)
    val line = edge * live * (amplitude * unrolled * wave + bump + whip) + PressDepth * press * exp(-d * d)
    // The hero has no ripple, so its line keeps the exact value it had before the ripple existed.
    if (ripple.amplitude == 0f) return line
    val window =
        smoothStep(ripple.start, ripple.start + EdgeFade, u) * (1f - smoothStep(ripple.end - EdgeFade, ripple.end, u))
    return line + ripple.amplitude * window * sin(Tau * ripple.cycles * (u - Middle))
}

private fun smoothStep(
    from: Float,
    to: Float,
    x: Float,
): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (SmoothA - SmoothB * t)
}

private fun DrawScope.drawSignalPath(
    frame: SignalFrame,
    color: Color,
) {
    val end = frame.cut.coerceAtMost(1f)
    val live = signalPath(frame, from = 0f, to = end - if (end < 1f) GapWidth else 0f)
    if (frame.glow > 0f) {
        drawPath(
            live,
            color.copy(alpha = color.alpha * PathGlowAlpha * frame.glow),
            style = Stroke(GlowSpread.toPx() * 2f, cap = StrokeCap.Round),
        )
    }
    drawPath(live, restBrush(frame.rest, color), style = Stroke(StrokeWidth.toPx(), cap = StrokeCap.Round))
    if (end < 1f) {
        drawPath(
            signalPath(frame, from = end + GapWidth, to = 1f),
            color.copy(alpha = color.alpha * TailAlpha),
            style = Stroke(StrokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** The line color, faint with faded ends as far as the line is at [rest]. Mirrors `calm` in the shader. */
private fun restBrush(
    rest: Float,
    color: Color,
): Brush {
    if (rest <= 0f) return SolidColor(color)
    val middle = color.copy(alpha = color.alpha * (1f - rest * (1f - RestAlpha)))
    val end = color.copy(alpha = color.alpha * (1f - rest))
    return Brush.horizontalGradient(0f to end, RestFade to middle, 1f - RestFade to middle, 1f to end)
}

private fun DrawScope.signalPath(
    frame: SignalFrame,
    from: Float,
    to: Float,
): Path {
    val path = Path()
    if (to <= from) return path
    for (step in 0..PathSteps) {
        val u = from + (to - from) * step / PathSteps
        val point = Offset(u * size.width, size.height * (Middle + frame.offsetAt(u)))
        if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    return path
}

/** Height of the signal band. Content that shares the band, such as the session clock, uses it too. */
val RipDpiSignalFieldHeight = 56.dp
private val StrokeWidth = 2.dp
private val GlowSpread = 6.dp

private const val Tau = (2.0 * PI).toFloat()
private const val NanosPerSecond = 1_000_000_000f

// Every wave frequency completes a whole number of cycles in this period, so the wrap is seamless.
private const val TimePeriod = 400f

// The still frame puts the connecting pulse in the middle of the line instead of under the edge fade.
private const val StillTime = 0.9f
private const val NoCut = 2f
private const val Middle = 0.5f

// The wave: a slow and a fast sine, plus gusting noise for a warning. The shader source repeats these values.
private const val SlowWeight = 0.6f
private const val SlowCycles = 1.6f
private const val SlowSpeed = 0.35f
private const val FastWeight = 0.4f
private const val FastCycles = 3.7f
private const val FastSpeed = 0.6f
private const val FastPhase = 1.3f
private const val NoiseCycles = 11f
private const val NoiseSpeed = 1.9f
private const val GustCycles = 0.7f
private const val GustSpeed = 0.23f
private const val PulseSpeed = 0.55f

// The cubic of a smooth step: 3t^2 - 2t^3.
private const val SmoothA = 3f
private const val SmoothB = 2f
private const val LiveAmplitude = 0.16f
private const val UnstableNoise = 0.5f
private const val BrokenGlow = 0.5f
private const val EdgeFade = 0.18f

// A resting line is decoration: the headline names the state. The shader source repeats these values.
private const val RestAlpha = 0.55f
private const val RestFade = 0.3f
private const val PulseWidth = 0.07f
private const val PulseHeight = 0.3f
private const val PressWidth = 0.12f
private const val PressDepth = 0.2f
private const val GapWidth = 0.02f

// The edge of the unrolling wave, and the recoil of a broken end. The shader source repeats these values.
private const val FrontSoft = 0.12f
private const val FrontOpen = 1f + FrontSoft
private const val KickHeight = 0.22f
private const val KickWidth = 0.12f
private const val KickCycles = 1.5f
private const val TailAlpha = 0.3f
private const val PathGlowAlpha = 0.12f
private const val PathSteps = 72

private const val SignalShaderSource = """
uniform float2 size;
uniform float time;
uniform float amp;
uniform float pulse;
uniform float noise;
uniform float cut;
uniform float glow;
uniform float2 press;
uniform float4 ripple;
uniform float rest;
uniform float front;
uniform float kick;
uniform float stroke;
uniform float spread;
layout(color) uniform half4 color;

const float TAU = 6.2831853;

float offsetAt(float u) {
    float edge = smoothstep(0.0, 0.18, u) * (1.0 - smoothstep(0.82, 1.0, u));
    float w = 0.6 * sin(TAU * (1.6 * u - 0.35 * time)) + 0.4 * sin(TAU * (3.7 * u - 0.6 * time) + 1.3);
    w += noise * sin(TAU * (11.0 * u + 1.9 * time)) * sin(TAU * (0.7 * u - 0.23 * time));
    float p = (u - fract(0.55 * time)) / 0.07;
    float bump = pulse * 0.3 * exp(-p * p) * sin(TAU * 0.5 * p);
    float d = (u - press.x) / 0.12;
    float window = smoothstep(ripple.z, ripple.z + 0.18, u) * (1.0 - smoothstep(ripple.w - 0.18, ripple.w, u));
    float standing = ripple.x * window * sin(TAU * ripple.y * (u - 0.5));
    float unrolled = 1.0 - smoothstep(front - 0.12, front, u);
    float whip = kick * 0.22 * exp(min(u - cut, 0.0) / 0.12) * sin(TAU * 1.5 * kick);
    return edge * step(u, cut) * (amp * unrolled * w + bump + whip) + 0.2 * press.y * exp(-d * d) + standing;
}

half4 main(float2 xy) {
    float u = xy.x / size.x;
    float y = size.y * (0.5 + offsetAt(u));
    float slope = size.y * (offsetAt(u + 1.0 / size.x) - offsetAt(u));
    float d = abs(xy.y - y) / sqrt(1.0 + slope * slope);
    float core = 1.0 - smoothstep(stroke, stroke + 1.0, d);
    // The glow fades out before the bounds, so the clip of the field never shows as a hard edge.
    float2 inset = min(xy, size - xy);
    float inside = smoothstep(0.0, 2.0 * spread, inset.x) * smoothstep(0.0, 2.0 * spread, inset.y);
    float halo = 0.35 * glow * inside * exp(-d * d / (2.0 * spread * spread));
    float tail = mix(0.3, 1.0, step(u, cut));
    float gap = smoothstep(0.0, 0.02, abs(u - cut));
    float ends = smoothstep(0.0, 0.3, u) * (1.0 - smoothstep(0.7, 1.0, u));
    float calm = mix(1.0, 0.55 * ends, rest);
    float a = clamp(core + halo, 0.0, 1.0) * tail * gap * calm * color.a;
    return half4(color.rgb * a, a);
}
"""

@Preview(name = "SignalField light", showBackground = true, widthDp = 360)
@Composable
private fun RipDpiSignalFieldLightPreview() {
    SignalFieldModes(theme = "light")
}

@Preview(name = "SignalField dark", showBackground = true, widthDp = 360)
@Composable
private fun RipDpiSignalFieldDarkPreview() {
    SignalFieldModes(theme = "dark")
}

@Composable
private fun SignalFieldModes(theme: String) {
    RipDpiComponentPreview(themePreference = theme) {
        val colors = RipDpiThemeTokens.colors
        RipDpiSignalField(RipDpiSignalMode.Idle, colors.outline)
        RipDpiSignalField(RipDpiSignalMode.Seeking, colors.signal)
        RipDpiSignalField(RipDpiSignalMode.Live, colors.signal)
        RipDpiSignalField(RipDpiSignalMode.Live, colors.signal, press = { 1f })
        RipDpiSignalField(RipDpiSignalMode.Unstable, colors.warning)
        RipDpiSignalField(RipDpiSignalMode.Broken, colors.destructive, breakAt = 0.7f)
        // The standing ripple of a line with no path: wide, fine, and limited to one half of the width.
        RipDpiSignalField(RipDpiSignalMode.Idle, colors.outline, ripple = previewRipple(cycles = 2f))
        RipDpiSignalField(RipDpiSignalMode.Idle, colors.outline, ripple = previewRipple(cycles = 7f))
        RipDpiSignalField(RipDpiSignalMode.Idle, colors.outline, ripple = previewRipple(cycles = 2f, start = 0.5f))
    }
}

private fun previewRipple(
    cycles: Float,
    start: Float = 0f,
) = RipDpiSignalRipple(amplitude = { PreviewRippleAmplitude }, cycles = { cycles }, start = { start })

private const val PreviewRippleAmplitude = 0.12f
