package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

enum class RipDpiMetricTone {
    Positive,
    Warning,
    Negative,
    Info,
    Neutral,
    Muted,
    Accent,
}

@Immutable
data class RipDpiMetricToneStyle(
    val container: Color,
    val content: Color,
)

@Composable
fun ripDpiMetricToneStyle(tone: RipDpiMetricTone): RipDpiMetricToneStyle {
    val colors = RipDpiThemeTokens.colors
    return when (tone) {
        RipDpiMetricTone.Positive -> {
            RipDpiMetricToneStyle(
                container = colors.muted,
                content = colors.success,
            )
        }

        RipDpiMetricTone.Warning -> {
            RipDpiMetricToneStyle(
                container = colors.warningContainer,
                content = colors.warning,
            )
        }

        RipDpiMetricTone.Negative -> {
            RipDpiMetricToneStyle(
                container = colors.destructiveContainer,
                content = colors.destructive,
            )
        }

        RipDpiMetricTone.Info -> {
            RipDpiMetricToneStyle(
                container = colors.infoContainer,
                content = colors.info,
            )
        }

        RipDpiMetricTone.Neutral -> {
            RipDpiMetricToneStyle(
                container = colors.inputBackground,
                content = colors.foreground,
            )
        }

        RipDpiMetricTone.Muted -> {
            RipDpiMetricToneStyle(
                container = colors.inputBackground,
                content = colors.mutedForeground,
            )
        }

        RipDpiMetricTone.Accent -> {
            RipDpiMetricToneStyle(
                container = colors.accent,
                content = colors.foreground,
            )
        }
    }
}

@Composable
fun RipDpiMetricSurface(
    tone: RipDpiMetricTone,
    shape: Shape,
    modifier: Modifier = Modifier,
    containerAlpha: Float = 1f,
    tonalElevation: Dp = 0.dp,
    content: @Composable (contentColor: Color) -> Unit,
) {
    RipDpiMetricSurface(
        style = ripDpiMetricToneStyle(tone),
        shape = shape,
        modifier = modifier,
        containerAlpha = containerAlpha,
        tonalElevation = tonalElevation,
        content = content,
    )
}

@Composable
fun RipDpiMetricSurface(
    style: RipDpiMetricToneStyle,
    shape: Shape,
    modifier: Modifier = Modifier,
    containerAlpha: Float = 1f,
    tonalElevation: Dp = 0.dp,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val motion = RipDpiThemeTokens.motion
    val animatedContainer by animateColorAsState(
        targetValue = style.container.copy(alpha = containerAlpha),
        animationSpec = motion.stateTween(),
        label = "metricSurfaceContainer",
    )
    val animatedContent by animateColorAsState(
        targetValue = style.content,
        animationSpec = motion.stateTween(),
        label = "metricSurfaceContent",
    )

    Surface(
        modifier = modifier,
        color = animatedContainer,
        contentColor = animatedContent,
        shape = shape,
        tonalElevation = tonalElevation,
    ) {
        content(animatedContent)
    }
}

@Composable
fun RipDpiMetricPill(
    text: String,
    tone: RipDpiMetricTone,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    shape: Shape? = null,
    paddingValues: PaddingValues? = null,
    textAlpha: Float = 1f,
) {
    val resolvedPadding =
        paddingValues ?: PaddingValues(
            horizontal = RipDpiThemeTokens.spacing.sm,
            vertical = RipDpiThemeTokens.spacing.xs,
        )
    RipDpiMetricSurface(
        tone = tone,
        shape = shape ?: RipDpiThemeTokens.shapes.full,
        modifier = modifier,
    ) { contentColor ->
        Text(
            text = text,
            modifier = Modifier.padding(resolvedPadding),
            style = textStyle ?: RipDpiThemeTokens.type.monoSmall,
            color = contentColor.copy(alpha = textAlpha),
        )
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true)
@Composable
private fun RipDpiMetricPillPreview() {
    RipDpiComponentPreview {
        RipDpiMetricPill(text = "Positive", tone = RipDpiMetricTone.Positive)
        RipDpiMetricPill(text = "Warning", tone = RipDpiMetricTone.Warning)
        RipDpiMetricPill(text = "Negative", tone = RipDpiMetricTone.Negative)
        RipDpiMetricPill(text = "Info", tone = RipDpiMetricTone.Info)
        RipDpiMetricPill(text = "Neutral", tone = RipDpiMetricTone.Neutral)
        RipDpiMetricPill(text = "Muted", tone = RipDpiMetricTone.Muted)
        RipDpiMetricPill(text = "Accent", tone = RipDpiMetricTone.Accent)
    }
}
