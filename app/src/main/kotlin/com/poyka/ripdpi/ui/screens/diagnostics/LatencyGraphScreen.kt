package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val MaxLatencySamples = 60
private const val LatencyAxisTickCount = 4
private const val ThresholdLineAlpha = 0.5f
private const val ThresholdDashMultiplier = 4f

/**
 * Presentation-only latency graph matching
 * `docs/design/rds/preview/vpn-latency-graph.html`.
 *
 * Renders a single-series Canvas RTT line plot with a dashed 100 ms
 * threshold band (warning color), a packet-loss bar row, and a legend
 * row showing Now / p99 / spike count. All visual values come from
 * [LatencyGraphState]; no live data access. Tokens read exclusively
 * from [RipDpiThemeTokens] and [RipDpiStroke].
 */
@Composable
fun LatencyGraphScreen(
    state: LatencyGraphState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(modifier = modifier.fillMaxWidth(), variant = RipDpiCardVariant.Outlined) {
        LatencyHeader(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        LatencyPlot(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        LatencyLossRow(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        LatencyLegend(state)
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.vpn_latency_graph_caption),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun LatencyHeader(state: LatencyGraphState) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        // p50 / p95 stat pill in the header (matches spec card top-right)
        Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            Text(
                text =
                    stringResource(
                        R.string.vpn_latency_graph_p50_format,
                        state.p50Label,
                    ),
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text = "·",
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text =
                    stringResource(
                        R.string.vpn_latency_graph_p95_format,
                        state.p95Label,
                    ),
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun LatencyPlot(state: LatencyGraphState) {
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.components.shapes
    val colors = RipDpiThemeTokens.colors
    val bgColor = colors.inputBackground
    val lineColor = colors.foreground
    val thresholdColor = colors.warning
    val lineStroke = RipDpiStroke.Thick

    // plotHeight = section(40) + xxxl(32) + xxl(24) + xs(4) = 100 dp
    val plotHeight = spacing.section + spacing.xxxl + spacing.xxl + spacing.xs

    val plotDescription = state.title
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(plotHeight),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(plotHeight)
                    .clip(
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(shapes.largeCornerRadius),
                    ).semantics { contentDescription = plotDescription },
        ) {
            // Background
            drawRect(color = bgColor)

            val samples = state.rttSamples
            if (samples.size < 2) return@Canvas

            val window =
                if (samples.size > MaxLatencySamples) {
                    samples.subList(samples.size - MaxLatencySamples, samples.size)
                } else {
                    samples
                }

            val maxVal = window.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            val xStep = size.width / (window.size - 1).toFloat()

            // Dashed threshold band — drawn at state.thresholdFraction height
            val thresholdY = size.height - state.thresholdFraction * size.height
            val dashLen = RipDpiStroke.Thin.toPx() * ThresholdDashMultiplier
            val gapLen = dashLen
            var dashX = 0f
            while (dashX < size.width) {
                drawLine(
                    color = thresholdColor.copy(alpha = ThresholdLineAlpha),
                    start = Offset(dashX, thresholdY),
                    end = Offset((dashX + dashLen).coerceAtMost(size.width), thresholdY),
                    strokeWidth = RipDpiStroke.Thin.toPx(),
                )
                dashX += dashLen + gapLen
            }

            // RTT line
            val rttPath = Path()
            window.forEachIndexed { i, v ->
                val x = i * xStep
                val y = size.height - (v / maxVal) * size.height
                if (i == 0) rttPath.moveTo(x, y) else rttPath.lineTo(x, y)
            }
            drawPath(
                path = rttPath,
                color = lineColor,
                style =
                    Stroke(
                        width = lineStroke.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
            )

            // Axis tick marks — faint vertical lines at 1/4 intervals
            for (t in 1 until LatencyAxisTickCount) {
                val x = size.width * t / LatencyAxisTickCount
                drawLine(
                    color = lineColor.copy(alpha = 0.06f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = RipDpiStroke.Hairline.toPx(),
                )
            }
        }
    }
}

@Composable
private fun LatencyLossRow(state: LatencyGraphState) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.components.shapes

    Column(modifier = Modifier.fillMaxWidth()) {
        // Label row: "Packet loss" on left, percentage on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.vpn_latency_graph_loss_label),
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text = state.packetLossLabel,
                style = type.monoSmall,
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(spacing.xs))
        // Loss bar: muted background, warning fill clipped to lossFraction
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(spacing.sm),
        ) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(spacing.sm)
                        .clip(
                            androidx.compose.foundation.shape
                                .RoundedCornerShape(shapes.compactCornerRadius),
                        ),
            ) {
                // Track
                drawRoundRect(
                    color = colors.inputBackground,
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(shapes.compactCornerRadius.toPx()),
                    size = Size(size.width, size.height),
                )
                // Fill — warning color at loss fraction
                val fillWidth = (size.width * state.lossFraction).coerceAtLeast(0f)
                if (fillWidth > 0f) {
                    drawRoundRect(
                        color = colors.warning,
                        cornerRadius =
                            androidx.compose.ui.geometry
                                .CornerRadius(shapes.compactCornerRadius.toPx()),
                        size = Size(fillWidth, size.height),
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyLegend(state: LatencyGraphState) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
        // Now
        LatencyLegendItem(
            label = stringResource(R.string.vpn_latency_graph_legend_now),
            value = state.nowLabel,
            valueColor = colors.foreground,
        )
        // p99
        LatencyLegendItem(
            label = stringResource(R.string.vpn_latency_graph_legend_p99),
            value = state.p99Label,
            valueColor = colors.foreground,
        )
        // Spikes > threshold
        LatencyLegendItem(
            label = stringResource(R.string.vpn_latency_graph_legend_spikes),
            value = state.spikeCountLabel,
            valueColor = colors.destructive,
        )
    }
}

@Composable
private fun LatencyLegendItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs),
    ) {
        Text(text = label, style = type.monoSmall, color = colors.mutedForeground)
        Text(text = value, style = type.monoSmall, color = valueColor)
    }
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * Presentation state for [LatencyGraphScreen].
 *
 * @param title Screen / card header text (e.g. "RTT & loss · last 60 s").
 * @param rttSamples Normalised RTT samples (ms), latest last. Values in [0..maxRtt].
 * @param thresholdFraction Fraction of plot height at which the 100 ms dashed band is drawn.
 * @param p50Label Formatted p50 RTT (e.g. "32 ms").
 * @param p95Label Formatted p95 RTT (e.g. "128 ms").
 * @param nowLabel Current RTT value (e.g. "32 ms").
 * @param p99Label Formatted p99 RTT (e.g. "184 ms").
 * @param spikeCountLabel Number of spikes above threshold (e.g. "3").
 * @param packetLossLabel Formatted packet loss percentage (e.g. "4.1%").
 * @param lossFraction Raw loss fraction in [0..1] for the loss bar fill.
 */
data class LatencyGraphState(
    val title: String,
    val rttSamples: ImmutableList<Float>,
    val thresholdFraction: Float,
    val p50Label: String,
    val p95Label: String,
    val nowLabel: String,
    val p99Label: String,
    val spikeCountLabel: String,
    val packetLossLabel: String,
    val lossFraction: Float,
)
