package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val ShimmerBandWidth = 0.4f
private const val ShimmerBaseAlpha = 0.25f
private const val ShimmerGradientMidpoint = 0.5f
private const val shimmerCompactWidth = 0.75f
private const val shimmerHalfWidth = 0.5f

/**
 * Modifier that overlays a horizontal shimmer band, sweeping left to
 * right at 1200ms linear, infinite. The band uses the muted-foreground
 * color at ~30% alpha so it reads on top of skeleton blocks without
 * fighting them.
 *
 * Matches `components-shimmer.html`.
 */
fun Modifier.ripDpiShimmer(): Modifier =
    composed {
        val motion = RipDpiThemeTokens.motion
        val isInspection = androidx.compose.ui.platform.LocalInspectionMode.current
        val baseColor = RipDpiThemeTokens.colors.mutedForeground.copy(alpha = ShimmerBaseAlpha)
        val transition = rememberInfiniteTransition(label = "shimmer")
        // On real devices, collapse targetValue to initialValue under reduced
        // motion so the band stays parked off-screen-left and never sweeps.
        // In inspection mode (Roborazzi / @Preview), always pass the full
        // targetValue so the composition shape matches the originally
        // blessed golden — actual playback doesn't happen under inspection
        // either way, so the visible frame is identical.
        val progress by transition.animateFloat(
            initialValue = -ShimmerBandWidth,
            targetValue =
                if (isInspection || motion.allowsInfiniteMotion) {
                    1f + ShimmerBandWidth
                } else {
                    -ShimmerBandWidth
                },
            animationSpec = motion.shimmerSpec(),
            label = "shimmerProgress",
        )
        drawWithCache {
            val width = size.width
            val bandPx = width * ShimmerBandWidth
            val startX = progress * width
            val brush =
                Brush.horizontalGradient(
                    0f to baseColor.copy(alpha = 0f),
                    ShimmerGradientMidpoint to baseColor,
                    1f to baseColor.copy(alpha = 0f),
                    startX = startX,
                    endX = startX + bandPx,
                )
            onDrawWithContent {
                drawContent()
                drawRect(brush = brush, topLeft = Offset.Zero, blendMode = BlendMode.SrcAtop)
            }
        }
    }

/** A skeleton placeholder rectangle that shimmers; for loading states. */
@Composable
fun RipDpiSkeletonBox(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(
                    color = RipDpiThemeTokens.colors.muted,
                    shape = RoundedCornerShape(RipDpiThemeTokens.spacing.xs),
                ).ripDpiShimmer(),
    )
}

@Preview(showBackground = true, name = "RipDpiShimmer skeleton (light)")
@Composable
private fun RipDpiShimmerLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiSkeletonBox(height = 14.dp)
            RipDpiSkeletonBox(height = 14.dp, modifier = Modifier.fillMaxWidth(shimmerCompactWidth))
            RipDpiSkeletonBox(height = 14.dp, modifier = Modifier.fillMaxWidth(shimmerHalfWidth))
            RipDpiSkeletonBox(height = 64.dp)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiShimmer skeleton (dark)")
@Composable
private fun RipDpiShimmerDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiSkeletonBox(height = 14.dp)
            RipDpiSkeletonBox(height = 64.dp)
        }
    }
}
