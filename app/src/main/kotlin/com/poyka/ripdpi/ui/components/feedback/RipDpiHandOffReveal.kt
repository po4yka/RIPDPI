package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalField
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HandOffRingWidth = 2.dp
private const val HandOffFallbackAnchorHeightFraction = 1f / 3f
private const val HandOffLineMiddle = 0.5f

// The share of the reveal in which the line travels. The destination opens in the rest.
private const val HandOffTravelShare = 0.6f
private const val NanosPerMilli = 1_000_000L

// Two frames at 60 Hz. The longest step of the motion that one frame can take.
private const val HandOffMaxFrameNanos = 32 * NanosPerMilli
private const val HandOffLongestStretch = 2

/** A signal line that takes part in a hand-off: its bounds in root coordinates, and its look. */
@Immutable
data class RipDpiHandOffLine(
    val bounds: Rect,
    val mode: RipDpiSignalMode,
    val color: Color,
    val breakAt: Float = HandOffLineMiddle,
)

/** What the hand-off layer draws at one moment. Positions are root coordinates. */
internal data class RipDpiHandOffFrame(
    val openingCenter: Offset,
    val openingRadius: Float,
    val ringCenter: Offset,
    val ringRadius: Float,
    val ringAlpha: Float,
    /** True when the ring sits on the center of the line and moves with it. */
    val ringOnLine: Boolean,
    /** Only the horizontal extent and the vertical center have a meaning. Null when no line is drawn. */
    val line: Rect?,
    val lineAlpha: Float,
    /** The share of the line's width that shows, from its center outward. */
    val lineReveal: Float,
)

/**
 * State of a hand-off between two parts of the product whose content is swapped, not navigated.
 * The source screen sets [anchor] and, if it shows a signal line, [sourceLine]. The owner of the swap calls
 * [start] in the composition of the swap. The destination sets [targetLine] if its line is on screen.
 * [RipDpiHandOffReveal] draws the reveal above both screens. With a [targetLine] the line and the ring travel
 * to it over the closed veil, and the destination then opens as a circle from the line. With none the
 * destination opens as a circle from [anchor].
 */
class RipDpiHandOffState {
    /** Center of the element the reveal opens from, in root coordinates. */
    var anchor: Offset = Offset.Unspecified

    /** Radius of that element in pixels. The reveal starts with a ring of this size in its place. */
    var anchorRadius: Float = 0f

    /** The line of the source screen, as it last showed. It stays after the source leaves, for [start]. */
    var sourceLine: RipDpiHandOffLine? = null

    /** The line of the destination, or null when the destination shows none. */
    var targetLine: RipDpiHandOffLine? by mutableStateOf(null)

    /** True while the layer draws the line on its way to [targetLine]. The destination hides its own line then. */
    var travels: Boolean by mutableStateOf(false)
        private set

    internal var progress by mutableFloatStateOf(1f)
    internal var epoch by mutableIntStateOf(0)

    /** The last [epoch] the reveal served, so a reveal that enters the composition again does not replay it. */
    internal var servedEpoch = 0
    internal var landingMuted = false
        private set

    private var origin by mutableStateOf<RipDpiHandOffLine?>(null)
    private var easing: Easing = LinearEasing

    val isRunning: Boolean
        get() = progress < 1f

    /** The look of the line the layer draws: the destination's on the way to it, else the source's. */
    internal val line: RipDpiHandOffLine?
        get() = targetLine.takeIf { travels } ?: origin

    /**
     * Does nothing while a reveal runs, so two triggers for one hand-off do not restart it. With [animated]
     * false the hand-off is a cut: nothing is drawn, and the reveal still reports the landing.
     */
    fun start(animated: Boolean = true) {
        if (isRunning) return
        origin = sourceLine
        travels = false
        landingMuted = false
        if (animated) progress = 0f
        epoch++
    }

    /**
     * Another feedback covers the landing, so the reveal gives none. It holds until the next [start], not only
     * while the reveal runs: the feedback can arrive between the last frame and the landing.
     */
    fun muteLanding() {
        landingMuted = true
    }

    /**
     * Fixes the kind of reveal before its first moving frame: the line travels if the destination has reported
     * its line by now. A line reported later does not change a reveal that is on its way. [easing] shapes the
     * travel and the opening, each on its own, over a [progress] that is linear in time.
     */
    internal fun settle(easing: Easing = LinearEasing) {
        this.easing = easing
        travels = targetLine != null
    }

    internal fun finish() {
        progress = 1f
        travels = false
    }

    internal fun frame(
        progress: Float,
        size: Size,
    ): RipDpiHandOffFrame {
        val from =
            anchor.takeIf { it.isSpecified }
                ?: Offset(size.width / 2f, size.height * HandOffFallbackAnchorHeightFraction)
        val target = targetLine.takeIf { travels }
        if (target == null) {
            val open = easing.transform(progress)
            val opening = reach(from, size) * open
            return RipDpiHandOffFrame(
                openingCenter = from,
                openingRadius = opening,
                ringCenter = from,
                ringRadius = max(opening, anchorRadius),
                ringAlpha = 1f - open,
                ringOnLine = false,
                // A source line with no destination line has nowhere to go: it fades with the ring.
                line = origin?.bounds,
                lineAlpha = 1f - open,
                lineReveal = 1f,
            )
        }
        val travel = easing.transform((progress / HandOffTravelShare).coerceAtMost(1f))
        val open = easing.transform(((progress - HandOffTravelShare) / (1f - HandOffTravelShare)).coerceAtLeast(0f))
        val to = target.bounds.center
        val ringCenter = lerp(from, to, travel)
        val source = origin?.bounds
        return RipDpiHandOffFrame(
            openingCenter = to,
            // Closed until the line has landed, so the line never crosses the content of the destination.
            openingRadius = reach(to, size) * open,
            ringCenter = ringCenter,
            ringRadius = anchorRadius * (1f - travel),
            ringAlpha = 1f,
            ringOnLine = true,
            // With no source line the line has its final width from the start and shows from its center outward.
            // A line that grew in width would squeeze the wave into the ring.
            line = source?.let { lerp(it, target.bounds, travel) } ?: target.bounds.translate(ringCenter - to),
            lineAlpha = 1f,
            lineReveal = if (source == null) travel else 1f,
        )
    }

    /** Distance from [center] to the farthest corner. */
    private fun reach(
        center: Offset,
        size: Size,
    ): Float = hypot(max(center.x, size.width - center.x), max(center.y, size.height - center.y))
}

/**
 * How much of the motion one frame plays. A slow frame counts as a short one, so the motion takes longer and
 * does not skip ahead; it still plays at half speed or faster, so the reveal takes at most twice its duration.
 */
internal fun handOffStep(frameNanos: Long): Long =
    max(min(frameNanos, HandOffMaxFrameNanos), frameNanos / HandOffLongestStretch)

/** Null where no hand-off layer exists (previews and tests): a screen then has nothing to report. */
val LocalRipDpiHandOff = staticCompositionLocalOf<RipDpiHandOffState?> { null }

/**
 * Put on the signal line of the destination. Reports the line to the hand-off layer, and hides it while the
 * layer draws it on its way, so the user sees one line.
 */
@Composable
fun Modifier.ripDpiHandOffTarget(
    mode: RipDpiSignalMode,
    color: Color,
    breakAt: Float = HandOffLineMiddle,
): Modifier {
    val handOff = LocalRipDpiHandOff.current ?: return this
    // The target is hidden while the line travels, and fades in when the line has landed on it.
    val shown =
        animateFloatAsState(
            targetValue = if (handOff.travels) 0f else 1f,
            animationSpec = if (handOff.travels) snap() else RipDpiThemeTokens.motion.quickTween(),
            label = "Hand-off target",
        )
    // A change of look does not move the line, so the position callback does not report it.
    SideEffect { handOff.targetLine = handOff.targetLine?.copy(mode = mode, color = color, breakAt = breakAt) }
    DisposableEffect(handOff) { onDispose { handOff.targetLine = null } }
    return onGloballyPositioned { coordinates ->
        // A line that is off screen or clipped has no place to travel to: the hand-off then opens as a circle only.
        val bounds = coordinates.boundsInRoot()
        val root =
            coordinates
                .findRootCoordinates()
                .size
                .toSize()
                .toRect()
        // Within a pixel: the bounds are fractional and the size is whole.
        val whole =
            abs(bounds.width - coordinates.size.width) < 1f && abs(bounds.height - coordinates.size.height) < 1f &&
                root.left <= bounds.left && root.top <= bounds.top &&
                bounds.right <= root.right && bounds.bottom <= root.bottom
        handOff.targetLine = if (whole) RipDpiHandOffLine(bounds, mode, color, breakAt) else null
    }.graphicsLayer { alpha = shown.value }
}

/**
 * Place above the content that is swapped. Draws nothing until [RipDpiHandOffState.start], and nothing
 * when animations are off: the hand-off is then a cut. It takes no input, so touches reach the content.
 *
 * @param onLanded called once for each hand-off that completes, not for one that is cancelled.
 */
@Composable
fun RipDpiHandOffReveal(
    state: RipDpiHandOffState,
    modifier: Modifier = Modifier,
    onLanded: () -> Unit = {},
) {
    val enabled = RipDpiThemeTokens.motion.animationsEnabled
    // Not a key: a motion change (the user turns animations off) must not replay a finished reveal.
    val motion by rememberUpdatedState(RipDpiThemeTokens.motion)
    val landed by rememberUpdatedState(onLanded)

    LaunchedEffect(state, state.epoch) {
        if (state.epoch == state.servedEpoch) return@LaunchedEffect
        state.servedEpoch = state.epoch
        try {
            if (state.isRunning && motion.animationsEnabled) {
                // The destination composes and reports its line in its first frames, which are slow. The reveal
                // holds the closed veil until then, for a bounded time, so those frames do not eat the motion.
                withTimeoutOrNull(motion.duration(motion.stateDurationMillis).toLong()) {
                    snapshotFlow { state.targetLine != null }.first { it }
                }
                val spec = motion.signatureTween<Float>()
                state.settle(spec.easing)
                val duration = spec.durationMillis * NanosPerMilli
                var played = 0L
                var last = withFrameNanos { it }
                while (state.isRunning) {
                    val now = withFrameNanos { it }
                    played += handOffStep(frameNanos = now - last)
                    last = now
                    state.progress = if (played >= duration) 1f else played.toFloat() / duration
                }
            }
            if (!state.landingMuted) landed()
        } finally {
            // Also on cancellation: an unfinished reveal must never leave anything drawn over the app.
            state.finish()
        }
    }

    RipDpiHandOffLayer(
        state = state,
        progress = { if (enabled) state.progress else 1f },
        modifier = modifier,
    )
}

/**
 * The reveal at one [progress] value: a veil in the page color with a circular opening, the line, and a ring
 * that marks the anchor. [RipDpiHandOffState.frame] places them.
 */
@Composable
internal fun RipDpiHandOffLayer(
    state: RipDpiHandOffState,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val veil = colors.background
    val ring = colors.foreground
    val opening = remember { Path() }
    val currentProgress by rememberUpdatedState(progress)
    val visible by remember { derivedStateOf { currentProgress() < 1f } }
    val line = state.line

    Box(
        modifier =
            modifier.fillMaxSize().drawWithContent {
                val fraction = progress()
                if (fraction >= 1f) return@drawWithContent
                val frame = state.frame(fraction, size)
                opening.rewind()
                opening.addOval(Rect(center = frame.openingCenter, radius = frame.openingRadius))
                clipPath(opening, clipOp = ClipOp.Difference) {
                    drawRect(veil)
                }
                drawContent()
                if (!frame.ringOnLine) {
                    drawCircle(
                        color = ring,
                        radius = frame.ringRadius,
                        center = frame.ringCenter,
                        alpha = frame.ringAlpha,
                        style = Stroke(width = HandOffRingWidth.toPx()),
                    )
                }
            },
    ) {
        if (visible && line != null) {
            RipDpiSignalField(
                mode = line.mode,
                color = line.color,
                breakAt = line.breakAt,
                modifier =
                    Modifier
                        .layout { measurable, constraints ->
                            val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                            val bounds = state.frame(progress(), size).line ?: Rect.Zero
                            val placeable =
                                measurable.measure(
                                    Constraints.fixedWidth(max(bounds.width.roundToInt(), 1)),
                                )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                // Root coordinates, so not mirrored in a right-to-left layout.
                                placeable.place(
                                    x = bounds.left.roundToInt(),
                                    y = (bounds.center.y - placeable.height / 2f).roundToInt(),
                                )
                            }
                        }.drawWithContent {
                            // These values do not depend on the size of the layer.
                            val frame = state.frame(progress(), Size.Zero)
                            val hidden = size.width * (1f - frame.lineReveal) / 2f
                            clipRect(left = hidden, right = size.width - hidden) {
                                this@drawWithContent.drawContent()
                            }
                            // On the node of the line, so the ring and the line move in the same frame.
                            if (frame.ringOnLine) {
                                drawCircle(
                                    color = ring,
                                    radius = frame.ringRadius,
                                    style = Stroke(width = HandOffRingWidth.toPx()),
                                )
                            }
                        }.graphicsLayer { alpha = state.frame(progress(), Size.Zero).lineAlpha },
            )
        }
    }
}

private const val PreviewWidth = 220f
private const val PreviewHeight = 320f
private const val PreviewLineHeight = 56f
private const val PreviewAnchorY = 80f
private const val PreviewTargetLeft = 55f
private const val PreviewTargetTop = 213f
private val PreviewProgress = listOf(0f, 0.35f, 0.7f)

/** The source line and the badge near the top, and, with [travels], the destination line lower and narrower. */
@Composable
private fun RipDpiHandOffPreviewBody(
    progress: Float,
    travels: Boolean,
) {
    val color = RipDpiThemeTokens.colors.signal
    val state =
        remember(color, travels) {
            RipDpiHandOffState().apply {
                anchor = Offset(PreviewWidth / 2f, PreviewAnchorY)
                anchorRadius = PreviewLineHeight / 2f
                sourceLine =
                    RipDpiHandOffLine(
                        bounds =
                            Rect(
                                center = anchor,
                                radius = PreviewLineHeight / 2f,
                            ).copy(left = 0f, right = PreviewWidth),
                        mode = RipDpiSignalMode.Live,
                        color = color,
                    )
                start()
                if (travels) {
                    targetLine =
                        sourceLine?.copy(
                            bounds =
                                Rect(
                                    left = PreviewTargetLeft,
                                    top = PreviewTargetTop,
                                    right = PreviewWidth,
                                    bottom = PreviewTargetTop + PreviewLineHeight,
                                ),
                        )
                    settle()
                }
            }
        }
    // The preview pane is in pixels, like the root coordinates of the state.
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(modifier = Modifier.size(width = PreviewWidth.dp, height = PreviewHeight.dp)) {
            RipDpiHandOffLayer(state = state, progress = { progress })
        }
    }
}

@Composable
private fun RipDpiHandOffPreviewRow(
    themePreference: String,
    travels: Boolean,
) {
    RipDpiTheme(themePreference = themePreference) {
        Row {
            PreviewProgress.forEach { progress -> RipDpiHandOffPreviewBody(progress = progress, travels = travels) }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffTravelPreview() {
    RipDpiHandOffPreviewRow(themePreference = "light", travels = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffTravelDarkPreview() {
    RipDpiHandOffPreviewRow(themePreference = "dark", travels = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffCirclePreview() {
    RipDpiHandOffPreviewRow(themePreference = "light", travels = false)
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffCircleDarkPreview() {
    RipDpiHandOffPreviewRow(themePreference = "dark", travels = false)
}
