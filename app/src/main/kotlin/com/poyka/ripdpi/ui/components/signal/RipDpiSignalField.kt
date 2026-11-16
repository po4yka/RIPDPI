package com.poyka.ripdpi.ui.components.signal

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
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
    /** No path: a flat line. */
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

private class SignalShape(
    val amplitude: Float,
    val pulse: Float,
    val noise: Float,
    val glow: Float,
    val broken: Boolean,
)

private fun RipDpiSignalMode.shape(): SignalShape =
    when (this) {
        RipDpiSignalMode.Idle -> SignalShape(0f, 0f, 0f, 0f, broken = false)
        RipDpiSignalMode.Seeking -> SignalShape(0f, 1f, 0f, 1f, broken = false)
        RipDpiSignalMode.Live -> SignalShape(LiveAmplitude, 0f, 0f, 1f, broken = false)
        RipDpiSignalMode.Unstable -> SignalShape(LiveAmplitude, 0f, UnstableNoise, 1f, broken = false)
        RipDpiSignalMode.Broken -> SignalShape(LiveAmplitude, 0f, 0f, BrokenGlow, broken = true)
    }

/** One frame of the line. Plain floats, so the draw phase reads them without a recomposition. */
private class SignalFrame(
    val time: Float,
    val amplitude: Float,
    val pulse: Float,
    val noise: Float,
    val cut: Float,
    val glow: Float,
    val pressAt: Float,
    val press: Float,
)

/**
 * The hero signal line. An AGSL shader draws the moving line on API 33 and later. A path draws the same curve below
 * that, and draws the still frame that each [mode] shows with animations off or reduced motion. Animated values
 * are read in the draw phase only. The line is decoration and has no semantics; the caller names the state.
 *
 * @param breakAt where the line stops in [RipDpiSignalMode.Broken], as a fraction of the width.
 * @param pressAt where the pointer is, as a fraction of the width.
 * @param press how far the line dips at [pressAt], from 0 to 1.
 */
@Composable
fun RipDpiSignalField(
    mode: RipDpiSignalMode,
    color: Color,
    modifier: Modifier = Modifier,
    breakAt: Float = Middle,
    pressAt: () -> Float = { Middle },
    press: () -> Float = { 0f },
) {
    val motion = RipDpiThemeTokens.motion
    val transition = updateTransition(targetState = mode, label = "Signal mode")
    val amplitude by transition.animateFloat({ motion.emphasizedTween() }, "amplitude") { it.shape().amplitude }
    val pulse by transition.animateFloat({ motion.emphasizedTween() }, "pulse") { it.shape().pulse }
    val noise by transition.animateFloat({ motion.emphasizedTween() }, "noise") { it.shape().noise }
    val glow by transition.animateFloat({ motion.emphasizedTween() }, "glow") { it.shape().glow }
    val cut by transition.animateFloat({ motion.emphasizedTween() }, "cut") {
        if (it.shape().broken) breakAt.coerceIn(0f, 1f) else NoCut
    }
    val lineColor by animateColorAsState(color, motion.emphasizedTween(), label = "Signal color")

    val time = remember { mutableFloatStateOf(StillTime) }
    val moving = motion.allowsInfiniteMotion && (mode != RipDpiSignalMode.Idle && mode != RipDpiSignalMode.Broken)
    if (moving) {
        LaunchedEffect(Unit) {
            // The infinite-animation clock lets a test or a host policy stop the loop, as for any infinite transition.
            // Continue from the last frame, so a restart after an idle or failed state does not jump the phase.
            val base = time.floatValue
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
                    val frame = SignalFrame(time.floatValue, amplitude, pulse, noise, cut, glow, pressAt(), press())
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
        shader.setFloatUniform("stroke", strokePx / 2f)
        shader.setFloatUniform("spread", spreadPx)
        shader.setColorUniform("color", color.toArgb())
        return brush
    }
}

/** Vertical offset of the line at [u], as a fraction of the height. Mirrors `offsetAt` in the shader. */
private fun SignalFrame.offsetAt(u: Float): Float {
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
    return edge * live * (amplitude * wave + bump) + PressDepth * press * exp(-d * d)
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
    drawPath(live, color, style = Stroke(StrokeWidth.toPx(), cap = StrokeCap.Round))
    if (end < 1f) {
        drawPath(
            signalPath(frame, from = end + GapWidth, to = 1f),
            color.copy(alpha = color.alpha * TailAlpha),
            style = Stroke(StrokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
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
private const val PulseWidth = 0.07f
private const val PulseHeight = 0.3f
private const val PressWidth = 0.12f
private const val PressDepth = 0.2f
private const val GapWidth = 0.02f
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
    return edge * step(u, cut) * (amp * w + bump) + 0.2 * press.y * exp(-d * d);
}

half4 main(float2 xy) {
    float u = xy.x / size.x;
    float y = size.y * (0.5 + offsetAt(u));
    float slope = size.y * (offsetAt(u + 1.0 / size.x) - offsetAt(u));
    float d = abs(xy.y - y) / sqrt(1.0 + slope * slope);
    float core = 1.0 - smoothstep(stroke, stroke + 1.0, d);
    float halo = 0.35 * glow * exp(-d * d / (2.0 * spread * spread));
    float tail = mix(0.3, 1.0, step(u, cut));
    float gap = smoothstep(0.0, 0.02, abs(u - cut));
    float a = clamp(core + halo, 0.0, 1.0) * tail * gap * color.a;
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
    }
}
