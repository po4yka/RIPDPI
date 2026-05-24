package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val HeartbeatCanvasScale = 3
private const val HeartbeatRingStrokeWidth = 1.5f
private const val HeartbeatHighlightAlpha = 0.3f
private const val HeartbeatHighlightRadiusScale = 0.4f
private const val HeartbeatHighlightOffsetScale = 0.2f

enum class RipDpiHeartbeatState {
    /** Active connection — pulses in success color. */
    Healthy,

    /** Degraded — pulses in warning color. */
    Degraded,

    /** Failed — solid destructive, no pulse. */
    Failed,

    /** Disconnected — solid muted, no pulse. */
    Idle,
}

/**
 * Cardiac pulse marker: a filled dot at the center with an expanding,
 * fading ring on each beat. Color is keyed by [RipDpiHeartbeatState];
 * Failed / Idle states are static (no animation).
 *
 * Matches `components-heartbeat-indicator.html`.
 */
@Composable
fun RipDpiHeartbeatIndicator(
    state: RipDpiHeartbeatState,
    modifier: Modifier = Modifier,
    diameter: Dp = 12.dp,
) {
    val colors = RipDpiThemeTokens.colors
    val dotColor =
        when (state) {
            RipDpiHeartbeatState.Healthy -> colors.success
            RipDpiHeartbeatState.Degraded -> colors.warning
            RipDpiHeartbeatState.Failed -> colors.destructive
            RipDpiHeartbeatState.Idle -> colors.mutedForeground
        }
    val pulsing = state == RipDpiHeartbeatState.Healthy || state == RipDpiHeartbeatState.Degraded
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val pulseProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = RipDpiThemeTokens.motion.pulseSpec(),
        label = "pulse",
    )
    Canvas(
        modifier =
            modifier
                .size(diameter * HeartbeatCanvasScale)
                .semantics { contentDescription = "Heartbeat ${state.name.lowercase()}" },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val coreRadius = diameter.toPx() / 2f
        if (pulsing) {
            val ringRadius = coreRadius + (size.width / 2f - coreRadius) * pulseProgress
            val ringAlpha = (1f - pulseProgress).coerceAtLeast(0f)
            drawCircle(
                color = dotColor.copy(alpha = ringAlpha),
                radius = ringRadius,
                center = center,
                style = Stroke(width = HeartbeatRingStrokeWidth),
            )
        }
        drawCircle(color = dotColor, radius = coreRadius, center = center)
        drawCircle(
            color = Color.White.copy(alpha = HeartbeatHighlightAlpha),
            radius = coreRadius * HeartbeatHighlightRadiusScale,
            center =
                center.copy(
                    x = center.x - coreRadius * HeartbeatHighlightOffsetScale,
                    y = center.y - coreRadius * HeartbeatHighlightOffsetScale,
                ),
        )
    }
}

@Preview(showBackground = true, name = "RipDpiHeartbeatIndicator (light)")
@Composable
private fun RipDpiHeartbeatIndicatorLightPreview() {
    RipDpiComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg)) {
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Healthy)
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Degraded)
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Failed)
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Idle)
        }
    }
}

@Preview(showBackground = true, name = "RipDpiHeartbeatIndicator (dark)")
@Composable
private fun RipDpiHeartbeatIndicatorDarkPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.lg)) {
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Healthy, diameter = 16.dp)
            RipDpiHeartbeatIndicator(state = RipDpiHeartbeatState.Failed, diameter = 16.dp)
        }
    }
}
