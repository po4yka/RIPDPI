package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.core.animate
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
import androidx.compose.runtime.staticCompositionLocalOf
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private val HandOffRingWidth = 2.dp
private const val HandOffFallbackAnchorHeightFraction = 1f / 3f
private const val HandOffLineMiddle = 0.5f

/** A signal line that takes part in a hand-off: its bounds in root coordinates, and its look. */
@Immutable
data class RipDpiHandOffLine(
    val bounds: Rect,
    val mode: RipDpiSignalMode,
    val color: Color,
    val breakAt: Float = HandOffLineMiddle,
)

/**
 * State of a hand-off between two parts of the product whose content is swapped, not navigated.
 * The source screen sets [anchor] and, if it shows a signal line, [sourceLine]. The owner of the swap calls
 * [start] in the composition of the swap. The destination sets [targetLine] if its line is on screen.
 * [RipDpiHandOffReveal] draws the reveal above both screens: the line and the ring travel to [targetLine]
 * while the destination opens as a circle from [anchor]. With no [targetLine] only the circle opens.
 */
class RipDpiHandOffState {
    /** Center of the element the reveal opens from, in root coordinates. */
    var anchor: Offset = Offset.Unspecified

    /** Radius of that element in pixels. The reveal starts with a ring of this size in its place. */
    var anchorRadius: Float = 0f

    /** The line of the source screen. The source clears it when the line leaves; [start] keeps the last one. */
    var sourceLine: RipDpiHandOffLine? = null

    /** The line of the destination, or null when the destination shows none. */
    var targetLine: RipDpiHandOffLine?
        get() = target
        set(value) {
            // The destination reports after its first layout. The travel starts there, so the line does not jump.
            if (target == null && value != null) travelFrom = if (isRunning) progress else 0f
            target = value
        }

    internal var progress by mutableFloatStateOf(1f)
    internal var epoch by mutableIntStateOf(0)

    /** The last [epoch] the reveal served, so a reveal that enters the composition again does not replay it. */
    internal var servedEpoch = 0
    internal var landingMuted = false
        private set

    private var target by mutableStateOf<RipDpiHandOffLine?>(null)
    private var origin by mutableStateOf<RipDpiHandOffLine?>(null)
    private var travelFrom = 0f

    val isRunning: Boolean
        get() = progress < 1f

    /** The look of the travelling line: the destination's when it is known, else the source's. */
    internal val line: RipDpiHandOffLine?
        get() = target ?: origin

    /**
     * Does nothing while a reveal runs, so two triggers for one hand-off do not restart it. With [animated]
     * false the hand-off is a cut: nothing is drawn, and the reveal still reports the landing.
     */
    fun start(animated: Boolean = true) {
        if (isRunning) return
        origin = sourceLine
        travelFrom = 0f
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

    internal fun center(size: Size): Offset =
        anchor.takeIf { it.isSpecified } ?: Offset(size.width / 2f, size.height * HandOffFallbackAnchorHeightFraction)

    /** How far the line and the ring are on their way to [targetLine], from 0 to 1. */
    internal fun travel(progress: Float): Float =
        if (target == null) 0f else ((progress - travelFrom) / (1f - travelFrom)).coerceIn(0f, 1f)

    /**
     * Radius and alpha of the ring. With no destination line it grows with the opening, of radius [opening],
     * and fades. With one it closes on the line, from the size and alpha it had when the destination reported.
     */
    internal fun ring(
        progress: Float,
        opening: Float,
        reach: Float,
    ): Pair<Float, Float> =
        if (target == null) {
            max(opening, anchorRadius) to 1f - progress
        } else {
            val travel = travel(progress)
            max(reach * travelFrom, anchorRadius) * (1f - travel) to 1f - travelFrom * (1f - travel)
        }

    internal fun ringCenter(
        progress: Float,
        size: Size,
    ): Offset = target?.let { lerp(center(size), it.bounds.center, travel(progress)) } ?: center(size)

    /**
     * Bounds of the travelling line, or null when no screen shows a line. Only the horizontal extent and the
     * vertical center have a meaning. With no [sourceLine] the line grows out of the ring.
     */
    internal fun lineBounds(
        progress: Float,
        size: Size,
    ): Rect? {
        val target = target
        val start = origin?.bounds ?: target?.let { center(size).let { center -> Rect(center, center) } } ?: return null
        return if (target == null) start else lerp(start, target.bounds, travel(progress))
    }
}

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
    }.graphicsLayer { alpha = if (handOff.isRunning && handOff.targetLine != null) 0f else 1f }
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
                animate(0f, 1f, animationSpec = motion.signatureTween()) { value, _ -> state.progress = value }
            }
            if (!state.landingMuted) landed()
        } finally {
            // Also on cancellation: an unfinished reveal must never leave anything drawn over the app.
            state.progress = 1f
        }
    }

    RipDpiHandOffLayer(
        state = state,
        progress = { if (enabled) state.progress else 1f },
        modifier = modifier,
    )
}

/**
 * The reveal at one [progress] value: a veil in the page color with a circular opening that grows from the
 * anchor, the travelling line, and a ring. The ring marks the anchor; it travels with the line and closes on
 * it, or, with no destination line, grows with the opening and fades.
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
                val center = state.center(size)
                val reach =
                    hypot(
                        max(center.x, size.width - center.x),
                        max(center.y, size.height - center.y),
                    )
                val radius = reach * fraction
                opening.rewind()
                opening.addOval(Rect(center = center, radius = radius))
                clipPath(opening, clipOp = ClipOp.Difference) {
                    drawRect(veil)
                }
                drawContent()
                val (ringRadius, ringAlpha) = state.ring(fraction, opening = radius, reach = reach)
                drawCircle(
                    color = ring,
                    radius = ringRadius,
                    center = state.ringCenter(fraction, size),
                    alpha = ringAlpha,
                    style = Stroke(width = HandOffRingWidth.toPx()),
                )
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
                            val bounds = state.lineBounds(progress(), size) ?: Rect.Zero
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
                        }.graphicsLayer {
                            // A source line with no destination line has nowhere to go: it fades with the ring.
                            alpha = if (state.targetLine == null) 1f - progress() else 1f
                        },
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
