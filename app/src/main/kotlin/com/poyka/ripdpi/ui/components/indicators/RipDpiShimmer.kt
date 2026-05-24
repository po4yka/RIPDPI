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

private const val SHIMMER_BAND_WIDTH = 0.4f

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
        val baseColor = RipDpiThemeTokens.colors.mutedForeground.copy(alpha = 0.25f)
        val transition = rememberInfiniteTransition(label = "shimmer")
        val progress by transition.animateFloat(
            initialValue = -SHIMMER_BAND_WIDTH,
            targetValue = 1f + SHIMMER_BAND_WIDTH,
            animationSpec = RipDpiThemeTokens.motion.shimmerSpec(),
            label = "shimmerProgress",
        )
        drawWithCache {
            val width = size.width
            val bandPx = width * SHIMMER_BAND_WIDTH
            val startX = progress * width
            val brush =
                Brush.horizontalGradient(
                    0f to baseColor.copy(alpha = 0f),
                    0.5f to baseColor,
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
private fun RipDpiShimmerPreviewLight() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiSkeletonBox(height = 14.dp)
            RipDpiSkeletonBox(height = 14.dp, modifier = Modifier.fillMaxWidth(0.75f))
            RipDpiSkeletonBox(height = 14.dp, modifier = Modifier.fillMaxWidth(0.5f))
            RipDpiSkeletonBox(height = 64.dp)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiShimmer skeleton (dark)")
@Composable
private fun RipDpiShimmerPreviewDark() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm)) {
            RipDpiSkeletonBox(height = 14.dp)
            RipDpiSkeletonBox(height = 64.dp)
        }
    }
}
