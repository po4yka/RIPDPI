package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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

@Composable
fun StatusIndicator(
    label: String,
    modifier: Modifier = Modifier,
    tone: StatusIndicatorTone = StatusIndicatorTone.Active,
) {
    val colors = RipDpiThemeTokens.colors
    val motion = RipDpiThemeTokens.motion
    val indicatorColor =
        when (tone) {
            StatusIndicatorTone.Active -> colors.foreground
            StatusIndicatorTone.Idle -> colors.mutedForeground
            StatusIndicatorTone.Warning -> colors.warning
            StatusIndicatorTone.Error -> colors.destructive
        }
    val animatedIndicatorColor by animateColorAsState(
        targetValue = indicatorColor,
        animationSpec = tween(durationMillis = motion.duration(motion.stateDurationMillis)),
        label = "statusIndicatorColor",
    )
    val pulseTransition =
        if (motion.allowsInfiniteMotion && tone != StatusIndicatorTone.Idle && tone != StatusIndicatorTone.Error) {
            rememberInfiniteTransition(label = "statusPulse")
        } else {
            null
        }
    val pulseScale by (
        pulseTransition?.animateFloat(
            initialValue = 1f,
            targetValue = 1.8f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = motion.duration(motion.emphasizedDurationMillis * 2),
                            easing = LinearEasing,
                        ),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "statusPulseScale",
        ) ?: rememberUpdatedState(1f)
        )
    val pulseAlpha by (
        pulseTransition?.animateFloat(
            initialValue = 0.22f,
            targetValue = 0f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        tween(
                            durationMillis = motion.duration(motion.emphasizedDurationMillis * 2),
                            easing = LinearEasing,
                        ),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "statusPulseAlpha",
        ) ?: rememberUpdatedState(0f)
        )

    val statusDescription = stringResource(R.string.status_indicator_description, label)
    Row(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = statusDescription
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pulseAlpha > 0f) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .scale(pulseScale)
                            .background(animatedIndicatorColor.copy(alpha = pulseAlpha), CircleShape),
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(animatedIndicatorColor, CircleShape),
            )
        }
        Text(
            text = label,
            style = RipDpiThemeTokens.type.brandStatus,
            color = colors.foreground,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusIndicatorPreview() {
    RipDpiComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusIndicator(label = "Running")
            StatusIndicator(label = "Idle", tone = StatusIndicatorTone.Idle)
            StatusIndicator(label = "Warning", tone = StatusIndicatorTone.Warning)
            StatusIndicator(label = "Error", tone = StatusIndicatorTone.Error)
        }
    }
}
