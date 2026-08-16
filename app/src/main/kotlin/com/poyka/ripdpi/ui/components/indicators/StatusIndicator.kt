package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class StatusIndicatorTone {
    Active,
    Idle,
    Warning,
    Error,
}

private data class StatusIndicatorAnimation(
    val animatedIndicatorColor: State<Color>,
    val pulseScale: State<Float>,
    val pulseAlpha: State<Float>,
    val pulsing: Boolean,
)

@Composable
fun StatusIndicator(
    label: String,
    modifier: Modifier = Modifier,
    tone: StatusIndicatorTone = StatusIndicatorTone.Active,
    /**
     * Whether the marker pulses, meaning the state it reports is changing right now. Tone cannot
     * answer that: it carries severity, and a stored result carries the same severity as a live one.
     * Deriving pulsing from tone was wrong in both directions -- a finished scan pulsed forever
     * inside a list item that stays on screen, while a running probe mapped to Idle and never
     * pulsed at all. Most indicators report a stored or configured state, so this stays false
     * unless the call site can name what is in flight.
     */
    pulsing: Boolean = false,
) {
    val colors = RipDpiThemeTokens.colors
    val indicatorColor =
        when (tone) {
            StatusIndicatorTone.Active -> colors.foreground
            StatusIndicatorTone.Idle -> colors.mutedForeground
            StatusIndicatorTone.Warning -> colors.warning
            StatusIndicatorTone.Error -> colors.destructive
        }
    val animation = rememberStatusIndicatorAnimation(indicatorColor, pulsing)
    val statusDescription = stringResource(R.string.status_indicator_description, label)
    val indicators = RipDpiThemeTokens.components.indicators

    Row(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = statusDescription
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(indicators.statusMarkerSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMarker(tone, animation)
        Text(
            text = label,
            style = RipDpiThemeTokens.type.brandStatus,
            color = colors.foreground,
        )
    }
}

@Composable
private fun rememberStatusIndicatorAnimation(
    indicatorColor: Color,
    requestedPulsing: Boolean,
): StatusIndicatorAnimation {
    val motion = RipDpiThemeTokens.motion
    val pulsing =
        requestedPulsing &&
            !LocalInspectionMode.current &&
            motion.allowsInfiniteMotion
    val animatedIndicatorColor =
        animateColorAsState(
            targetValue = indicatorColor,
            animationSpec = motion.stateTween(),
            label = "statusIndicatorColor",
        )
    val pulseTransition = if (pulsing) rememberInfiniteTransition(label = "statusPulse") else null
    val pulseScale =
        pulseTransition?.animateFloat(
            initialValue = 1f,
            targetValue = 1.8f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        motion.durationTween(
                            baseDurationMillis = motion.emphasizedDurationMillis * 2,
                            easing = LinearEasing,
                        ),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "statusPulseScale",
        ) ?: rememberUpdatedState(1f)
    val pulseAlpha =
        pulseTransition?.animateFloat(
            initialValue = 0.22f,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        motion.durationTween(
                            baseDurationMillis = motion.emphasizedDurationMillis * 2,
                            easing = LinearEasing,
                        ),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "statusPulseAlpha",
        ) ?: rememberUpdatedState(0f)
    return StatusIndicatorAnimation(animatedIndicatorColor, pulseScale, pulseAlpha, pulsing)
}

@Composable
private fun StatusMarker(
    tone: StatusIndicatorTone,
    animation: StatusIndicatorAnimation,
) {
    val indicators = RipDpiThemeTokens.components.indicators
    Box(contentAlignment = Alignment.Center) {
        if (animation.pulsing) {
            Canvas(modifier = Modifier.size(indicators.statusMarkerSmall)) {
                drawCircle(
                    color = animation.animatedIndicatorColor.value.copy(alpha = animation.pulseAlpha.value),
                    radius = size.minDimension / 2f * animation.pulseScale.value,
                )
            }
        }
        val markerSize =
            when (tone) {
                StatusIndicatorTone.Active, StatusIndicatorTone.Error -> indicators.statusMarkerSmall
                StatusIndicatorTone.Idle -> indicators.statusMarkerMedium
                StatusIndicatorTone.Warning -> indicators.statusMarkerLarge
            }
        Canvas(modifier = Modifier.size(markerSize)) {
            drawStatusMarker(tone, animation.animatedIndicatorColor.value)
        }
    }
}

private fun DrawScope.drawStatusMarker(
    tone: StatusIndicatorTone,
    color: Color,
) {
    when (tone) {
        StatusIndicatorTone.Active -> {
            drawCircle(color)
        }

        StatusIndicatorTone.Error -> {
            drawRect(color)
        }

        StatusIndicatorTone.Warning -> {
            val path =
                Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            drawPath(path, color)
        }

        StatusIndicatorTone.Idle -> {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = size.width / 2f
            val path =
                Path().apply {
                    moveTo(centerX, centerY - radius)
                    lineTo(centerX + radius, centerY)
                    lineTo(centerX, centerY + radius)
                    lineTo(centerX - radius, centerY)
                    close()
                }
            drawPath(path, color)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusIndicatorPreview() {
    RipDpiComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusIndicator(label = "Running", pulsing = true)
            StatusIndicator(label = "Idle", tone = StatusIndicatorTone.Idle)
            StatusIndicator(label = "Warning", tone = StatusIndicatorTone.Warning)
            StatusIndicator(label = "Error", tone = StatusIndicatorTone.Error)
        }
    }
}
