package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.hypot
import kotlin.math.max

private val HandOffRingWidth = 2.dp
private const val HandOffFallbackAnchorHeightFraction = 1f / 3f

/**
 * State of a hand-off between two parts of the product whose content is swapped, not navigated.
 * The source screen sets [anchor]; the owner of the swap calls [start] in the frame of the swap, and
 * [RipDpiHandOffReveal] draws the reveal above both screens.
 */
class RipDpiHandOffState {
    /** Center of the element the reveal opens from, in root coordinates. */
    var anchor: Offset = Offset.Unspecified

    /** Radius of that element in pixels. The reveal starts with a ring of this size in its place. */
    var anchorRadius: Float = 0f

    internal var progress by mutableFloatStateOf(1f)
    internal var epoch by mutableIntStateOf(0)

    val isRunning: Boolean
        get() = progress < 1f

    /** Does nothing while a reveal runs, so two triggers for one hand-off do not restart it. */
    fun start() {
        if (isRunning) return
        progress = 0f
        epoch++
    }
}

/** Null where no hand-off layer exists (previews and tests): a source then has no anchor to report. */
val LocalRipDpiHandOff = staticCompositionLocalOf<RipDpiHandOffState?> { null }

/**
 * Place above the content that is swapped. Draws nothing until [RipDpiHandOffState.start], and nothing
 * when animations are off: the hand-off is then a cut.
 */
@Composable
fun RipDpiHandOffReveal(
    state: RipDpiHandOffState,
    modifier: Modifier = Modifier,
) {
    val enabled = RipDpiThemeTokens.motion.animationsEnabled
    // Not a key: a motion change (the user turns animations off) must not replay a finished reveal.
    val motion by rememberUpdatedState(RipDpiThemeTokens.motion)

    LaunchedEffect(state, state.epoch) {
        if (state.epoch == 0) return@LaunchedEffect
        try {
            if (motion.animationsEnabled) {
                animate(0f, 1f, animationSpec = motion.signatureTween()) { value, _ -> state.progress = value }
            }
        } finally {
            // Also on cancellation: an unfinished reveal must never leave the veil over the app.
            state.progress = 1f
        }
    }

    RipDpiHandOffVeil(
        progress = { if (enabled) state.progress else 1f },
        anchor = { state.anchor },
        anchorRadius = { state.anchorRadius },
        modifier = modifier,
    )
}

/**
 * The reveal at one [progress] value: a veil in the page color with a circular opening that grows from [anchor].
 * A ring of [anchorRadius] marks the anchor until the opening passes it.
 */
@Composable
internal fun RipDpiHandOffVeil(
    progress: () -> Float,
    anchor: () -> Offset,
    anchorRadius: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val veil = colors.background
    val ring = colors.foreground
    val opening = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val fraction = progress()
        if (fraction >= 1f) return@Canvas
        val center =
            anchor().takeIf { it.isSpecified }
                ?: Offset(size.width / 2f, size.height * HandOffFallbackAnchorHeightFraction)
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
        drawCircle(
            color = ring,
            radius = max(radius, anchorRadius()),
            center = center,
            alpha = 1f - fraction,
            style = Stroke(width = HandOffRingWidth.toPx()),
        )
    }
}

@Composable
private fun RipDpiHandOffVeilPreviewBody(progress: Float) {
    Box(modifier = Modifier.size(width = 220.dp, height = 320.dp)) {
        RipDpiHandOffVeil(
            progress = { progress },
            anchor = { Offset.Unspecified },
            anchorRadius = { 56f },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffVeilStartPreview() {
    RipDpiTheme(themePreference = "light") { RipDpiHandOffVeilPreviewBody(progress = 0f) }
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffVeilMidPreview() {
    RipDpiTheme(themePreference = "light") { RipDpiHandOffVeilPreviewBody(progress = 0.35f) }
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffVeilStartDarkPreview() {
    RipDpiTheme(themePreference = "dark") { RipDpiHandOffVeilPreviewBody(progress = 0f) }
}

@Preview(showBackground = true, backgroundColor = 0xFF808080)
@Composable
private fun RipDpiHandOffVeilMidDarkPreview() {
    RipDpiTheme(themePreference = "dark") { RipDpiHandOffVeilPreviewBody(progress = 0.35f) }
}
