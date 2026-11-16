package com.poyka.ripdpi.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.feedback.LocalRipDpiHandOff
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlin.math.abs
import kotlin.math.floor

internal val OnboardingStepBadgeSize = 56.dp
internal val OnboardingStepBadgeCompactSize = 40.dp

private const val BadgeIconFraction = 0.5f
private const val BadgeIconRestScale = 0.6f
private const val BadgeDragShrink = 0.08f
private const val PreviewBetweenStepsPosition = 1.5f

/**
 * Where the step badge is in the window. The activity provides it, so that the splash screen can wait for the
 * badge and move its icon to it. Touch it on the main thread only.
 */
@Stable
internal class OnboardingBadgeAnchor {
    /** The badge bounds in the window, in pixels, or null until the badge is placed. */
    var bounds: Rect? = null
}

/** Null in previews and tests, where no splash screen exists. */
internal val LocalOnboardingBadgeAnchor = staticCompositionLocalOf<OnboardingBadgeAnchor?> { null }

/**
 * The one element that stays in place through every onboarding step. Its icon follows [pagePosition], the
 * fractional pager position, which is read in the draw phase so a drag does not recompose. The badge is also
 * the anchor from which the main screen opens when onboarding completes.
 */
@Composable
internal fun OnboardingStepBadge(
    pagePosition: () -> Float,
    succeeded: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = OnboardingStepBadgeSize,
) {
    val colors = RipDpiThemeTokens.colors
    val handOff = LocalRipDpiHandOff.current
    val splashAnchor = LocalOnboardingBadgeAnchor.current
    Box(
        modifier =
            modifier
                .size(size)
                .onGloballyPositioned { coordinates ->
                    handOff?.anchor = coordinates.boundsInRoot().center
                    handOff?.anchorRadius = coordinates.size.width / 2f
                    splashAnchor?.bounds = coordinates.boundsInWindow()
                }.graphicsLayer {
                    // Smallest halfway between two steps, full size at rest.
                    val position = pagePosition()
                    val betweenSteps = 1f - abs(2f * (position - floor(position)) - 1f)
                    val scale = 1f - BadgeDragShrink * betweenSteps
                    scaleX = scale
                    scaleY = scale
                }.background(colors.muted, RipDpiThemeTokens.shapes.full)
                // Decoration: each page states its step in its title.
                .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        OnboardingPages.forEachIndexed { index, page ->
            Icon(
                imageVector = if (succeeded && page.isConnectionTest) RipDpiIcons.Check else page.icon,
                contentDescription = null,
                tint = colors.foreground,
                modifier =
                    Modifier
                        .size(size * BadgeIconFraction)
                        .graphicsLayer {
                            val weight = (1f - abs(pagePosition() - index)).coerceIn(0f, 1f)
                            val scale = lerp(BadgeIconRestScale, 1f, weight)
                            alpha = weight
                            scaleX = scale
                            scaleY = scale
                        },
            )
        }
    }
}

@Composable
private fun OnboardingStepBadgePreviewBody(themePreference: String) {
    RipDpiComponentPreview(themePreference = themePreference) {
        // Each step at rest, halfway between two steps, the passed connection test, and the compact size.
        OnboardingPages.indices.forEach { index ->
            OnboardingStepBadge(pagePosition = { index.toFloat() }, succeeded = false)
        }
        OnboardingStepBadge(pagePosition = { PreviewBetweenStepsPosition }, succeeded = false)
        OnboardingStepBadge(pagePosition = { OnboardingTestStep.toFloat() }, succeeded = true)
        OnboardingStepBadge(pagePosition = { 0f }, succeeded = false, size = OnboardingStepBadgeCompactSize)
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingStepBadgePreview() {
    OnboardingStepBadgePreviewBody(themePreference = "light")
}

@Preview(showBackground = true)
@Composable
private fun OnboardingStepBadgeDarkPreview() {
    OnboardingStepBadgePreviewBody(themePreference = "dark")
}
