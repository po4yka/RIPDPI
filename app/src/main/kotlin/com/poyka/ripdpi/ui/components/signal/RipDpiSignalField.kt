package com.poyka.ripdpi.ui.components.signal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/** The state of the signal line. The caller picks the color; the line never carries state alone. */
enum class RipDpiSignalMode {
    /** No path: a faint flat line that fades out at both ends. */
    Idle,

    /** A path is being set up: one packet travels along the line. */
    Seeking,

    /** A path is live: packets travel along the line, as many as the traffic. */
    Live,

    /** A path is live with a warning: some of the packets are lost on the way. */
    Unstable,

    /** The path failed: the line stops at [RipDpiSignalField]'s `breakAt` and a dim line follows. */
    Broken,
}

private fun RipDpiSignalMode.carriesPackets(): Boolean =
    this == RipDpiSignalMode.Seeking || this == RipDpiSignalMode.Live || this == RipDpiSignalMode.Unstable

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
    /** Where the line stops, as a fraction of the width. [NoCut] is a whole line. */
    val cut: Float = NoCut,
    val pressAt: Float = Middle,
    val press: Float = 0f,
    /** How far the line is at rest, from 0 to 1. A resting line is faint and fades out at both ends. */
    val rest: Float = 0f,
    val ripple: SignalRippleFrame = NoRipple,
    /** The recoil of the end of a line that has just broken, from 1 at the break to 0 at rest. */
    val kick: Float = 0f,
)

/** One packet on the line. [head] is its leading edge and [length] its size, as fractions of the width. */
internal class SignalPacket(
    var head: Float,
    val length: Float,
    /** Where the packet is lost. [NoCut] is a packet that arrives. */
    val lostAt: Float = NoCut,
)

/** How visible the packet is. It fades in at the start of the line, and out at its end, its loss, or the [cut]. */
internal fun SignalPacket.alpha(cut: Float): Float {
    val enter = (head / PacketFade).coerceIn(0f, 1f)
    val leave = ((1f + length - head) / PacketFade).coerceIn(0f, 1f)
    val stop = minOf(lostAt, cut)
    return enter * leave * (1f - smoothStep(stop - PacketFade, stop, head))
}

/**
 * The packets on the line. They enter at the device end and leave at the network end, so a line that goes live
 * fills from the start, and a line that stops carrying packets empties by itself.
 */
internal class SignalPacketStream(
    private val random: Random = Random(StreamSeed),
) {
    val packets = ArrayList<SignalPacket>()
    private var untilNext = 0f
    private var probed = false

    /**
     * Moves the packets by [seconds] and sends the next one when it is due. [traffic] is the measured level from
     * 0 to 1; without traffic a live line sends one keep-alive packet at a long interval. [origin] is where the
     * first packet of a connection attempt starts.
     */
    fun advance(
        seconds: Float,
        mode: RipDpiSignalMode,
        traffic: Float,
        origin: Float,
    ) {
        val step = PacketSpeed * seconds
        packets.forEach { it.head += step }
        packets.removeAll { it.head - it.length >= 1f || it.head >= it.lostAt }
        when (mode) {
            RipDpiSignalMode.Seeking -> {
                if (packets.isEmpty()) {
                    packets += SignalPacket(head = if (probed) 0f else origin.coerceIn(0f, 1f), length = ProbeLength)
                    probed = true
                }
            }

            RipDpiSignalMode.Live, RipDpiSignalMode.Unstable -> {
                probed = false
                // A rise in traffic shows at once, not after the long interval of a quiet line.
                untilNext = minOf(untilNext, gap(traffic) * (JitterFrom + JitterSpan)) - seconds
                if (untilNext <= 0f) {
                    packets += next(lossy = mode == RipDpiSignalMode.Unstable)
                    untilNext = gap(traffic) * (JitterFrom + JitterSpan * random.nextFloat())
                }
            }

            RipDpiSignalMode.Idle, RipDpiSignalMode.Broken -> {
                probed = false
                untilNext = 0f
            }
        }
    }

    private fun next(lossy: Boolean): SignalPacket {
        val length = ShortPacket + (LongPacket - ShortPacket) * random.nextFloat()
        val lost = lossy && random.nextFloat() < LossShare
        return SignalPacket(head = 0f, length, if (lost) LossFrom + LossSpan * random.nextFloat() else NoCut)
    }

    private fun gap(traffic: Float): Float =
        if (traffic <= 0f) KeepAliveGap else QuietGap + (BusyGap - QuietGap) * traffic.coerceAtMost(1f)
}

/** The packets of a line that does not move: the frame that [mode] shows with animations off or reduced motion. */
internal fun stillPackets(mode: RipDpiSignalMode): List<SignalPacket> =
    when (mode) {
        RipDpiSignalMode.Seeking -> {
            listOf(SignalPacket(head = Middle + ProbeLength / 2f, length = ProbeLength))
        }

        RipDpiSignalMode.Live, RipDpiSignalMode.Unstable -> {
            StillHeads.mapIndexed { index, head ->
                val lost = mode == RipDpiSignalMode.Unstable && index % 2 == 1
                SignalPacket(head, StillLengths[index], if (lost) head + PacketFade / 2f else NoCut)
            }
        }

        RipDpiSignalMode.Idle, RipDpiSignalMode.Broken -> {
            emptyList()
        }
    }

/** Turns the packet counter of a session into the traffic level of the line, from 0 to 1. */
internal class SignalTrafficMeter {
    private var packets = -1L
    private var at = 0L

    /** The level since the last call. The first call, and a counter that went back, start a new baseline at 0. */
    fun level(
        totalPackets: Long,
        atMillis: Long,
    ): Float {
        val moved = totalPackets - packets
        val elapsed = atMillis - at
        val known = packets >= 0 && moved >= 0 && elapsed > 0
        packets = totalPackets
        at = atMillis
        if (!known) return 0f
        val perSecond = moved * MillisPerSecond / elapsed
        // A logarithmic scale: a few packets a second already show, and a busy line does not turn solid.
        return (log10(1f + perSecond) / log10(1f + FullPacketsPerSecond)).coerceIn(0f, 1f)
    }
}

/**
 * The hero signal line: a straight line from the device to the network, with the packets that travel on it. The
 * packets move only while the app allows infinite motion; otherwise each [mode] shows a still frame. Animated
 * values are read in the draw phase only. The line is decoration and has no semantics; the caller names the state.
 *
 * @param breakAt where the line stops in [RipDpiSignalMode.Broken], as a fraction of the width.
 * @param pressAt where the pointer is, as a fraction of the width.
 * @param press how far the line dips at [pressAt], from 0 to 1.
 * @param ripple a standing ripple for a line with no path. The default draws the line as before.
 * @param traffic the measured traffic level from 0 to 1. A caller with no measurement keeps the default.
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
    traffic: () -> Float = { UnmeasuredTraffic },
) {
    val motion = RipDpiThemeTokens.motion
    val transition = updateTransition(targetState = mode, label = "Signal mode")
    val rest by transition.animateFloat({ motion.emphasizedTween() }, "rest") {
        if (it == RipDpiSignalMode.Idle) 1f else 0f
    }
    // Under packets the line is a track, so it steps back and the packets carry the color.
    val track by transition.animateFloat({ motion.emphasizedTween() }, "track") {
        if (it.carriesPackets()) TrackAlpha else 1f
    }
    val cut by transition.animateFloat({ motion.emphasizedTween() }, "cut") {
        if (it == RipDpiSignalMode.Broken) breakAt.coerceIn(0f, 1f) else NoCut
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

    val moves = motion.allowsInfiniteMotion
    val stream = remember { SignalPacketStream() }
    val still = remember(mode, moves) { if (moves) null else stillPackets(mode) }
    val frames = remember { mutableIntStateOf(0) }
    val currentTraffic by rememberUpdatedState(traffic)
    val currentPressAt by rememberUpdatedState(pressAt)
    if (moves) {
        LaunchedEffect(mode) {
            // The infinite-animation clock lets a test or a host policy stop the loop, as for any infinite transition.
            var last = withInfiniteAnimationFrameNanos { it }
            // A line that stops carrying packets keeps moving until the last one has left.
            while (mode.carriesPackets() || stream.packets.isNotEmpty()) {
                withInfiniteAnimationFrameNanos { now ->
                    val seconds = ((now - last) / NanosPerSecond).coerceAtMost(LongestStep)
                    stream.advance(seconds, mode, currentTraffic(), currentPressAt())
                    last = now
                    frames.intValue++
                }
            }
        }
    }

    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RipDpiSignalFieldHeight)
                .clipToBounds()
                .drawBehind {
                    // Each frame of the stream redraws the line.
                    frames.intValue
                    val rippleFrame =
                        ripple?.let { SignalRippleFrame(it.amplitude(), it.cycles(), it.start(), it.end()) }
                            ?: NoRipple
                    // A rippled line is content, not a resting hero line or a track, so it keeps its full strength.
                    val plain = ripple == null
                    val frame = SignalFrame(cut, pressAt(), press(), if (plain) rest else 0f, rippleFrame, kick.value)
                    drawSignalLine(frame, lineColor, strength = if (plain) track else 1f)
                    // The packets that are still on a line whose path has ended fade out with it.
                    drawPackets(still ?: stream.packets, frame, lineColor, strength = 1f - rest)
                },
    )
}

/** Vertical offset of the line at [u], as a fraction of the height. */
internal fun SignalFrame.offsetAt(u: Float): Float {
    val d = (u - pressAt) / PressWidth
    val whip = if (u <= cut) kick * KickHeight * exp((u - cut) / KickWidth) * sin(Tau * KickCycles * kick) else 0f
    val line = whip + PressDepth * press * exp(-d * d)
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

private fun DrawScope.drawSignalLine(
    frame: SignalFrame,
    color: Color,
    strength: Float,
) {
    val end = frame.cut.coerceAtMost(1f)
    val live = signalPath(frame, from = 0f, to = end - if (end < 1f) GapWidth else 0f)
    val lineColor = color.copy(alpha = color.alpha * strength)
    drawPath(live, restBrush(frame.rest, lineColor), style = Stroke(StrokeWidth.toPx(), cap = StrokeCap.Round))
    if (end < 1f) {
        drawPath(
            signalPath(frame, from = end + GapWidth, to = 1f),
            color.copy(alpha = color.alpha * TailAlpha),
            style = Stroke(StrokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawPackets(
    packets: List<SignalPacket>,
    frame: SignalFrame,
    color: Color,
    strength: Float,
) {
    val height = PacketHeight.toPx()
    val corner = CornerRadius(PacketCorner.toPx())
    // Indexed, so a frame allocates no iterator.
    for (index in packets.indices) {
        val packet = packets[index]
        val alpha = packet.alpha(frame.cut) * strength
        if (alpha > 0f) {
            val middle = size.height * (Middle + frame.offsetAt(packet.head - packet.length / 2f))
            drawRoundRect(
                color = color,
                topLeft = Offset((packet.head - packet.length) * size.width, middle - height / 2f),
                size = Size(packet.length * size.width, height),
                cornerRadius = corner,
                alpha = alpha,
            )
        }
    }
}

/** The line color, faint with faded ends as far as the line is at [rest]. */
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
private val PacketHeight = 6.dp
private val PacketCorner = 2.dp

private const val Tau = (2.0 * PI).toFloat()
private const val NanosPerSecond = 1_000_000_000f
private const val MillisPerSecond = 1_000f
private const val NoCut = 2f
private const val Middle = 0.5f

// The cubic of a smooth step: 3t^2 - 2t^3.
private const val SmoothA = 3f
private const val SmoothB = 2f
private const val EdgeFade = 0.18f

// A resting line is decoration: the headline names the state.
private const val RestAlpha = 0.55f
private const val RestFade = 0.3f
private const val TrackAlpha = 0.35f
private const val PressWidth = 0.12f
private const val PressDepth = 0.2f
private const val GapWidth = 0.02f
private const val TailAlpha = 0.3f
private const val PathSteps = 72

// The recoil of a broken end.
private const val KickHeight = 0.22f
private const val KickWidth = 0.12f
private const val KickCycles = 1.5f

// The packets. Sizes and positions are fractions of the width, speed is widths in a second, gaps are seconds.
private const val StreamSeed = 7
private const val PacketSpeed = 0.42f
private const val PacketFade = 0.06f
private const val ProbeLength = 0.08f
private const val ShortPacket = 0.04f
private const val LongPacket = 0.11f
private const val KeepAliveGap = 4f
private const val QuietGap = 1.2f
private const val BusyGap = 0.1f
private const val JitterFrom = 0.6f
private const val JitterSpan = 0.8f
private const val LossShare = 0.45f
private const val LossFrom = 0.35f
private const val LossSpan = 0.45f

// A frame that comes late moves the packets by no more than this, so they do not jump.
private const val LongestStep = 0.05f

// The line of a caller that measures nothing, such as the first launch: a calm, even stream.
private const val UnmeasuredTraffic = 0.35f
private const val FullPacketsPerSecond = 2_000f
private val StillHeads = floatArrayOf(0.2f, 0.42f, 0.6f, 0.86f)
private val StillLengths = floatArrayOf(0.05f, 0.08f, 0.04f, 0.07f)

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
