package com.poyka.ripdpi.ui.components.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.poyka.ripdpi.ui.components.RipDpiComponentPreview
import com.poyka.ripdpi.ui.components.RipDpiControlDensity
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.theme.RipDpiIconSizes
import com.poyka.ripdpi.ui.theme.RipDpiIcons
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * [Nominal] is deliberately neutral rather than green: it reports that the
 * indicators are in range, which is the resting state, and a success colour
 * would give the resting state more weight than an actual problem.
 */
enum class RipDpiDegradationTone { Nominal, Warning, Critical }

data class RipDpiDegradationMetric(
    val label: String,
    val value: String,
    val delta: String,
    val deltaIsBad: Boolean,
)

data class RipDpiDegradationAction(
    val label: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod")
@Composable
fun RipDpiDegradationStrip(
    title: String,
    body: String,
    metrics: ImmutableList<RipDpiDegradationMetric>,
    sinceLabel: String,
    primaryAction: RipDpiDegradationAction,
    secondaryAction: RipDpiDegradationAction?,
    modifier: Modifier = Modifier,
    tone: RipDpiDegradationTone = RipDpiDegradationTone.Warning,
) {
    val colors = RipDpiThemeTokens.colors
    val components = RipDpiThemeTokens.components
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val containerColor =
        when (tone) {
            RipDpiDegradationTone.Nominal -> colors.muted
            RipDpiDegradationTone.Warning -> colors.warningContainer
            RipDpiDegradationTone.Critical -> colors.destructiveContainer
        }
    val contentColor =
        when (tone) {
            RipDpiDegradationTone.Nominal -> colors.cardForeground
            RipDpiDegradationTone.Warning -> colors.warningContainerForeground
            RipDpiDegradationTone.Critical -> colors.destructiveContainerForeground
        }
    val accentColor =
        when (tone) {
            RipDpiDegradationTone.Nominal -> colors.mutedForeground
            RipDpiDegradationTone.Warning -> colors.warning
            RipDpiDegradationTone.Critical -> colors.destructive
        }
    val accentForeground =
        when (tone) {
            RipDpiDegradationTone.Nominal -> colors.card
            RipDpiDegradationTone.Warning -> colors.warningForeground
            RipDpiDegradationTone.Critical -> colors.destructiveForeground
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.xl,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(spacing.xs)
                        .fillMaxHeight()
                        .background(accentColor),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(components.feedback.decorativeBadgeSize)
                                .background(accentColor, shapes.full),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = RipDpiIcons.Warning,
                            contentDescription = null,
                            tint = accentForeground,
                            modifier = Modifier.size(RipDpiIconSizes.Small),
                        )
                    }
                    Text(
                        text = title,
                        style = type.bodyEmphasis,
                        color = contentColor,
                    )
                }
                Text(
                    text = body,
                    style = type.secondaryBody,
                    color = colors.mutedForeground,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    metrics.forEach { metric ->
                        RipDpiDegradationMetricChip(metric = metric)
                    }
                }
                Text(
                    text = sinceLabel,
                    style = type.caption,
                    color = colors.mutedForeground,
                )
            }
            Column(
                modifier = Modifier.padding(end = spacing.md, top = spacing.md, bottom = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (secondaryAction != null) {
                    RipDpiButton(
                        text = secondaryAction.label,
                        onClick = secondaryAction.onClick,
                        variant = RipDpiButtonVariant.Outline,
                        density = RipDpiControlDensity.Compact,
                    )
                }
                RipDpiButton(
                    text = primaryAction.label,
                    onClick = primaryAction.onClick,
                    variant = RipDpiButtonVariant.Primary,
                    density = RipDpiControlDensity.Compact,
                )
            }
        }
    }
}

@Composable
private fun RipDpiDegradationMetricChip(metric: RipDpiDegradationMetric) {
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.shapes
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val deltaColor = if (metric.deltaIsBad) colors.destructive else colors.success

    Surface(
        shape = shapes.md,
        color = colors.inputBackground,
        contentColor = colors.foreground,
        border = BorderStroke(RipDpiStroke.Thin, colors.border),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = metric.label,
                style = type.smallLabel,
                color = colors.mutedForeground,
            )
            Text(
                text = metric.value,
                style = type.monoSmall,
                color = colors.foreground,
            )
            Text(
                text = metric.delta,
                style = type.monoSmall,
                color = deltaColor,
            )
        }
    }
}

private val sampleMetrics =
    persistentListOf(
        RipDpiDegradationMetric(
            label = "Loss",
            value = "4.1%",
            delta = "+3.2 pp",
            deltaIsBad = true,
        ),
        RipDpiDegradationMetric(
            label = "RTT p50",
            value = "240 ms",
            delta = "+120",
            deltaIsBad = true,
        ),
        RipDpiDegradationMetric(
            label = "Jitter",
            value = "18 ms",
            delta = "+7",
            deltaIsBad = true,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun RipDpiDegradationStripWarningPreview() {
    RipDpiComponentPreview {
        RipDpiDegradationStrip(
            title = "Connected · quality degraded",
            body = "Latency and loss are above baseline since 12:14.",
            metrics = sampleMetrics,
            sinceLabel = "Since 12:14",
            primaryAction = RipDpiDegradationAction(label = "Reprobe", onClick = {}),
            secondaryAction = RipDpiDegradationAction(label = "Dismiss", onClick = {}),
            tone = RipDpiDegradationTone.Warning,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RipDpiDegradationStripCriticalPreview() {
    RipDpiComponentPreview(themePreference = "dark") {
        RipDpiDegradationStrip(
            title = "Connected · severe degradation",
            body = "Sustained loss and elevated RTT suggest active throttling.",
            metrics = sampleMetrics,
            sinceLabel = "Since 12:14",
            primaryAction = RipDpiDegradationAction(label = "Reprobe", onClick = {}),
            secondaryAction = null,
            tone = RipDpiDegradationTone.Critical,
        )
    }
}
