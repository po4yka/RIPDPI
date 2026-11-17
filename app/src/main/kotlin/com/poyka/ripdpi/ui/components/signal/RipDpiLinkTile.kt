package com.poyka.ripdpi.ui.components.signal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * The hero link tile: the path as a plug and a socket. With no session the plug lies out of the socket. A live
 * session seats it, and packets travel in the cable: sent packets in the upper lane, received packets in the lower
 * lane. The socket has one lamp for each stage of the path.
 *
 * The parts move only while the app allows infinite motion; otherwise each state shows a still frame. Animated
 * values are read in the draw phase only. The tile is decoration and has no semantics; the caller names the state.
 *
 * @param stages the state of each stage of the path, in order. The tile takes the first five.
 * @param tone the color of the packets. Lamps take the signal, warning, and destructive roles from their stage.
 * @param reserve the share of the width at the start edge that other content takes, from 0 to 1. The figure keeps
 * to the rest, so the caller can put the session clock in the tile.
 * @param press how far the connection action is pressed, from 0 to 1. A loose plug leans toward the socket.
 * @param pressed true while the connection action is pressed, so the tile takes frames for [press].
 * @param sent the measured level of sent traffic from 0 to 1.
 * @param received the measured level of received traffic, as [sent].
 * @param onPlug called when a drag puts the plug into the socket or takes it out. Null turns the drag off.
 */
@Composable
fun RipDpiLinkTile(
    mode: RipDpiLinkMode,
    stages: ImmutableList<RipDpiLinkStage>,
    tone: Color,
    modifier: Modifier = Modifier,
    reserve: () -> Float = { 0f },
    press: () -> Float = { 0f },
    pressed: Boolean = false,
    sent: () -> Float = { 0f },
    received: () -> Float = { 0f },
    onPlug: (() -> Unit)? = null,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    // Read in the draw phase, so a change of color does not recompose the tile.
    val container = linkContainer(mode)
    val pulseColor = animateColorAsState(tone, motion.stateTween(), label = "Link tone")
    val palette = remember(colors) { LinkPalette(colors) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lamps = remember(stages) { (stages + List(LinkStageCount) { RipDpiLinkStage.Pending }).take(LinkStageCount) }

    val moves = motion.allowsInfiniteMotion
    val drag = remember { mutableStateOf<Float?>(null) }
    val dragging by remember { derivedStateOf { drag.value != null } }
    val nudge = plugNudge(enabled = onPlug != null && moves && mode == RipDpiLinkMode.Open)
    // The cable in dp: where it starts, and how long it is. The draw phase measures it for the next frame.
    val span = remember { LinkSpan() }
    val frames = remember { mutableIntStateOf(0) }
    val currentPress by rememberUpdatedState(press)
    val currentSent by rememberUpdatedState(sent)
    val currentReceived by rememberUpdatedState(received)
    val currentReserve by rememberUpdatedState(reserve)
    val input = {
        LinkInput(
            mode = mode,
            stages = lamps,
            sent = currentSent(),
            received = currentReceived(),
            press = maxOf(currentPress(), nudge.value.value),
            drag = drag.value,
            length = span.length.floatValue,
            probeFrom = LeadStart - span.from.floatValue,
        )
    }
    // The tile first shows the still frame of its state, so a tile that takes no frames is still correct.
    val figure = remember(motion) { LinkFigure(motion = motion).apply { settle(input()) } }
    if (moves) {
        LaunchedEffect(figure, mode, lamps, pressed, dragging, nudge.active) {
            // A press starts before the plug moves, so the loop also runs while the action is pressed.
            figure.run(input, held = { pressed || nudge.active }) { frames.intValue++ }
        }
    }

    val still = remember { LinkStill() }
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RipDpiLinkTileHeight)
                .clip(RipDpiThemeTokens.shapes.md)
                .then(plugDrag(mode, enabled = onPlug != null && moves, rtl, figure, drag, span, onPlug))
                .drawWithCache {
                    val shapes = LinkShapes(this)
                    val edge = Stroke(1.dp.toPx())
                    val corner = CornerRadius(shapes.unit * TileCorner)
                    onDrawBehind {
                        // Each frame of the figure redraws the tile.
                        frames.intValue
                        val widthDp = size.width / shapes.unit
                        val taken = widthDp * currentReserve().coerceIn(0f, 1f)
                        // The figure sits in the middle of the width that is left.
                        val shift = taken + (widthDp - taken) / 2f - FigureMiddle
                        // Beside other content the cable starts at that content, and fades into the tile there.
                        // Otherwise it starts beyond the start edge of the tile, whatever the width.
                        span.from.floatValue = if (taken > 0f) taken - shift else -shift - EdgeBeyond
                        span.shift.floatValue = shift
                        span.length.floatValue = widthDp - shift + EdgeBeyond - span.from.floatValue
                        drawRect(container.value)
                        drawRoundRect(palette.border, style = edge, cornerRadius = corner)
                        // With no motion the tile shows the still frame of its state, and renews it only on a change.
                        if (!moves) still.settle(figure, input())
                        // A right-to-left layout mirrors the figure: the device is at the start edge.
                        scale(if (rtl) -1f else 1f, 1f) {
                            translate(left = shift * shapes.unit) {
                                val cover = if (taken > 0f) container.value else Color.Unspecified
                                drawLink(figure, shapes, palette, pulseColor.value, span, cover)
                            }
                        }
                    }
                },
    )
}

/**
 * Moves the figure frame by frame until nothing moves. A live link with an empty cable at rest takes no frame
 * until its next packet is due.
 */
private suspend fun LinkFigure.run(
    input: () -> LinkInput,
    held: () -> Boolean,
    onFrame: () -> Unit,
) {
    var last = withInfiniteAnimationFrameNanos { it }
    while (held() || moving(input())) {
        val quiet = quiet(input())
        if (quiet > QuietWorth) {
            delay((quiet * MillisPerSecond).toLong())
            skip(quiet)
            last = withInfiniteAnimationFrameNanos { it }
        } else {
            withInfiniteAnimationFrameNanos { now ->
                advance(((now - last) / NanosPerSecond).coerceAtMost(LongestStep), input())
                last = now
                onFrame()
            }
        }
    }
}

private fun RipDpiLinkMode.live(): Boolean = this == RipDpiLinkMode.Live || this == RipDpiLinkMode.Unstable

/** Where the cable is on the tile, in dp of the design: its start, its length, and the shift of the design. */
internal class LinkSpan {
    val from = mutableFloatStateOf(EdgeFrom)
    val length = mutableFloatStateOf(LinkDefaultLength)
    val shift = mutableFloatStateOf(0f)
    val to: Float get() = from.floatValue + length.floatValue
}

@Composable
private fun linkContainer(mode: RipDpiLinkMode): State<Color> {
    val colors = RipDpiThemeTokens.colors
    return animateColorAsState(
        when (mode) {
            RipDpiLinkMode.Unstable -> colors.warningContainer
            RipDpiLinkMode.Broken -> colors.destructiveContainer
            else -> colors.card
        },
        RipDpiThemeTokens.motion.stateTween(),
        label = "Link container",
    )
}

/** The hint that the plug can be moved: a loose plug leans toward the socket once, the first time it is shown. */
private class PlugNudge(
    val value: State<Float>,
    val active: Boolean,
)

@Composable
private fun plugNudge(enabled: Boolean): PlugNudge {
    val motion = RipDpiThemeTokens.motion
    var shown by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    val lean = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (enabled && !shown) {
            delay(NudgeDelayMillis)
            active = true
            lean.animateTo(1f, motion.emphasizedTween())
            lean.animateTo(0f, motion.emphasizedTween())
            active = false
            shown = true
        }
    }
    return PlugNudge(lean.asState(), active)
}

/**
 * A drag of the plug: into the socket with no session, out of it with a live session. Only a touch on the plug
 * moves it, so a scroll that starts elsewhere on the tile stays a scroll.
 */
@Composable
private fun plugDrag(
    mode: RipDpiLinkMode,
    enabled: Boolean,
    rtl: Boolean,
    figure: LinkFigure,
    drag: MutableState<Float?>,
    span: LinkSpan,
    onPlug: (() -> Unit)?,
): Modifier {
    val open = mode == RipDpiLinkMode.Open
    val drags = enabled && (open || mode.live())
    val currentOnPlug by rememberUpdatedState(onPlug)
    // A drag that the state ends, or a drag that has acted, lets the plug go.
    DisposableEffect(mode, drags) { onDispose { drag.value = null } }
    val acted = remember { mutableIntStateOf(0) }
    LaunchedEffect(acted.intValue) {
        if (acted.intValue > 0) {
            delay(DragHoldMillis)
            drag.value = null
        }
    }
    if (!drags) return Modifier
    return Modifier.pointerInput(open, rtl) {
        val unit = 1.dp.toPx()
        val side = if (rtl) -1f else 1f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = figure.pull.at.coerceIn(0f, LinkGap)
            val x = (if (rtl) size.width - down.position.x else down.position.x) / unit
            val plugFrom = span.shift.floatValue + PlugNose + start - PlugTouch
            val slop =
                if (x in plugFrom..plugFrom + PlugEnd - PlugNose + PlugTouch * 2f) {
                    awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                } else {
                    null
                }
            if (slop != null) {
                drag.value = start
                val ended =
                    horizontalDrag(slop.id) { change ->
                        val moved = change.positionChange().x / unit * side
                        change.consume()
                        drag.value = ((drag.value ?: start) + moved).coerceIn(0f, LinkGap)
                    }
                val at = drag.value ?: start
                // Into the socket connects; out of it asks to disconnect. Anything less springs back.
                val far = if (open) at < LinkGap * PlugInShare else at > LinkGap * PlugOutShare
                // A plug that has acted stays where the drag left it until the state follows, or for a short time.
                drag.value = if (ended && far) (if (open) 0f else LinkGap) else null
                if (ended && far) {
                    acted.intValue++
                    currentOnPlug?.invoke()
                }
            }
        }
    }
}

/** Keeps the still frame of the figure, and settles the figure again only when the frame would differ. */
private class LinkStill {
    private var mode: RipDpiLinkMode? = null
    private var stages: List<RipDpiLinkStage>? = null
    private var length = 0f
    private var sent = -1
    private var received = -1
    private var pressed = false

    fun settle(
        figure: LinkFigure,
        input: LinkInput,
    ) {
        val nowSent = ceil(input.sent.coerceIn(0f, 1f) * StillSteps).toInt()
        val nowReceived = ceil(input.received.coerceIn(0f, 1f) * StillSteps).toInt()
        val nowPressed = input.press > HalfPress
        val same =
            mode == input.mode && stages == input.stages && length == input.length && sent == nowSent &&
                received == nowReceived && pressed == nowPressed
        if (same) return
        mode = input.mode
        stages = input.stages
        length = input.length
        sent = nowSent
        received = nowReceived
        pressed = nowPressed
        figure.settle(input)
    }
}

/**
 * The tone between [a] and [b] that has the same contrast to both. The socket takes it between the plug and the
 * tile, so the three stay apart at every contrast level of the theme.
 */
internal fun linkMidTone(
    a: Color,
    b: Color,
): Color {
    var low = 0f
    var high = 1f
    repeat(MidToneSteps) {
        val t = (low + high) / 2f
        val tone = lerp(a, b, t)
        if (linkContrast(tone, a) < linkContrast(tone, b)) low = t else high = t
    }
    return lerp(a, b, (low + high) / 2f)
}

internal fun linkContrast(
    a: Color,
    b: Color,
): Float {
    val one = a.luminance()
    val two = b.luminance()
    return (maxOf(one, two) + ContrastOffset) / (minOf(one, two) + ContrastOffset)
}

/** Height of the link tile. */
val RipDpiLinkTileHeight = 80.dp

// A drag: how far in connects, how far out disconnects, the reach of a touch around the plug, and how long a plug
// that has acted waits for the state to follow.
private const val PlugInShare = 0.55f
private const val PlugOutShare = 0.6f
private const val PlugTouch = 20f
private const val TileCorner = 12f
private const val DragHoldMillis = 1_500L
private const val NudgeDelayMillis = 700L
private const val StillSteps = 4f
private const val HalfPress = 0.5f

private const val MidToneSteps = 14
private const val ContrastOffset = 0.05f

private const val NanosPerSecond = 1_000_000_000f
private const val MillisPerSecond = 1_000f

// A frame that comes late moves the figure by no more than this, so it does not jump.
private const val LongestStep = 0.05f

// A wait shorter than this is not worth a pause of the frame loop.
private const val QuietWorth = 0.1f

@Preview(name = "LinkTile light", showBackground = true, widthDp = 360)
@Composable
private fun RipDpiLinkTileLightPreview() {
    LinkTileModes(theme = "light")
}

@Preview(name = "LinkTile dark", showBackground = true, widthDp = 360)
@Composable
private fun RipDpiLinkTileDarkPreview() {
    LinkTileModes(theme = "dark")
}

@Composable
private fun LinkTileModes(theme: String) {
    RipDpiComponentPreview(themePreference = theme) {
        val colors = RipDpiThemeTokens.colors

        fun stages(stage: (Int) -> RipDpiLinkStage) = List(LinkStageCount, stage).toImmutableList()
        Column {
            RipDpiLinkTile(RipDpiLinkMode.Open, stages { RipDpiLinkStage.Pending }, colors.outline)
            RipDpiLinkTile(RipDpiLinkMode.Seeking, stages { RipDpiLinkStage.Active }, colors.signal)
            RipDpiLinkTile(
                RipDpiLinkMode.Live,
                stages { RipDpiLinkStage.Complete },
                colors.signal,
                reserve = { PreviewReserve },
                sent = { PreviewSent },
                received = { PreviewReceived },
            )
            RipDpiLinkTile(
                RipDpiLinkMode.Unstable,
                stages { if (it == PreviewWarningAt) RipDpiLinkStage.Warning else RipDpiLinkStage.Complete },
                colors.warning,
                sent = { PreviewSent },
                received = { PreviewSent },
            )
            RipDpiLinkTile(
                RipDpiLinkMode.Broken,
                stages { if (it == PreviewFailedAt) RipDpiLinkStage.Failed else RipDpiLinkStage.Pending },
                colors.destructive,
            )
            RipDpiLinkTile(RipDpiLinkMode.Held, stages { RipDpiLinkStage.Held }, colors.signal)
        }
    }
}

private const val PreviewSent = 0.4f
private const val PreviewReceived = 0.8f
private const val PreviewReserve = 0.4f
private const val PreviewWarningAt = 1
private const val PreviewFailedAt = 3
