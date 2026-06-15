package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val MaxPlotSamples = 60
private const val QualityAxisTickCount = 4

/**
 * Presentation-only RTT + jitter graphs matching the Canvas line-plot pattern
 * established by [ThroughputGraphScreen] and [LatencyGraphScreen].
 *
 * Renders two [RipDpiCard]-wrapped charts driven by an
 * [ImmutableList] of [ConnectionQualitySnapshot] values:
 * - RTT chart: plots [ConnectionQualitySnapshot.rttP50Ms] (ms on Y, time on X),
 *   with a fill under the line, axis ticks, header pill, and legend row.
 * - Jitter chart: same layout for [ConnectionQualitySnapshot.jitterMs].
 *
 * Loss is surfaced by the home degradation strip; this screen currently charts
 * RTT and jitter only.
 * All visual values read exclusively from [RipDpiThemeTokens] and [RipDpiStroke].
 */
@Composable
fun QualityGraphsScreen(samples: ImmutableList<ConnectionQualitySnapshot>) {
    val spacing = RipDpiThemeTokens.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        QualityGraphCard(
            title = stringResource(R.string.vpn_quality_graph_throughput_title),
            nowLabel =
                samples
                    .lastOrNull()
                    ?.let { stringResource(R.string.vpn_quality_graph_value_ms, it.rttP50Ms) } ?: "—",
            p50Label =
                samples
                    .lastOrNull()
                    ?.let { stringResource(R.string.vpn_quality_graph_p50_ms, it.rttP50Ms) } ?: "—",
            samples = samples,
            selector = { it.rttP50Ms },
        )
        QualityGraphCard(
            title = stringResource(R.string.vpn_quality_graph_latency_title),
            nowLabel =
                samples
                    .lastOrNull()
                    ?.let { stringResource(R.string.vpn_quality_graph_value_ms, it.jitterMs) } ?: "—",
            p50Label =
                samples
                    .lastOrNull()
                    ?.let { stringResource(R.string.vpn_quality_graph_avg_ms, it.jitterMs) } ?: "—",
            samples = samples,
            selector = { it.jitterMs },
        )
    }
}

@Composable
private fun QualityGraphCard(
    title: String,
    nowLabel: String,
    p50Label: String,
    samples: ImmutableList<ConnectionQualitySnapshot>,
    selector: (ConnectionQualitySnapshot) -> Long,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(modifier = Modifier.fillMaxWidth(), variant = RipDpiCardVariant.Outlined) {
        QualityGraphHeader(title = title, nowLabel = nowLabel, p50Label = p50Label)
        Spacer(modifier = Modifier.height(spacing.sm))
        QualityLinePlot(samples = samples, selector = selector, description = title)
        Spacer(modifier = Modifier.height(spacing.sm))
        QualityLegend(nowLabel = nowLabel, p50Label = p50Label)
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.vpn_quality_graph_caption),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun QualityGraphHeader(
    title: String,
    nowLabel: String,
    p50Label: String,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = type.bodyEmphasis,
            color = colors.foreground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            Text(
                text = nowLabel,
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text = "·",
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
            Text(
                text = p50Label,
                style = type.monoSmall,
                color = colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun QualityLinePlot(
    samples: ImmutableList<ConnectionQualitySnapshot>,
    selector: (ConnectionQualitySnapshot) -> Long,
    description: String,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.components.shapes
    val colors = RipDpiThemeTokens.colors
    val bgColor = colors.inputBackground
    val lineColor = colors.accent
    val lineStroke = RipDpiStroke.Thick

    // plotHeight = section(40) + xxxl(32) + xxl(24) + xs(4) = 100 dp
    val plotHeight = spacing.section + spacing.xxxl + spacing.xxl + spacing.xs

    Box(
        modifier =
            modifier
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
                    ).semantics { contentDescription = description },
        ) {
            drawRect(color = bgColor)

            val window =
                if (samples.size > MaxPlotSamples) {
                    samples.subList(samples.size - MaxPlotSamples, samples.size)
                } else {
                    samples
                }
            val values = window.map { selector(it) }

            if (values.size >= 2) {
                drawQualityCanvas(
                    values = values,
                    lineColor = lineColor,
                    lineStroke = lineStroke,
                )
            }
        }
    }
}

private fun DrawScope.drawQualityCanvas(
    values: List<Long>,
    lineColor: androidx.compose.ui.graphics.Color,
    lineStroke: androidx.compose.ui.unit.Dp,
) {
    val maxValue = values.max().coerceAtLeast(1L).toFloat()
    val xStep = size.width / (values.size - 1).toFloat()

    // Fill area — 8% opacity under the curve (mirrors ThroughputGraphScreen pattern)
    val fillPath = Path()
    values.forEachIndexed { i, v ->
        val x = i * xStep
        val y = size.height - (v.toFloat() / maxValue) * size.height
        if (i == 0) fillPath.moveTo(x, y) else fillPath.lineTo(x, y)
    }
    fillPath.lineTo(size.width, size.height)
    fillPath.lineTo(0f, size.height)
    fillPath.close()
    drawPath(path = fillPath, color = lineColor.copy(alpha = 0.08f))

    // Line
    val linePath = Path()
    values.forEachIndexed { i, v ->
        val x = i * xStep
        val y = size.height - (v.toFloat() / maxValue) * size.height
        if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
    }
    drawPath(
        path = linePath,
        color = lineColor,
        style =
            Stroke(
                width = lineStroke.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
    )

    // Axis tick marks — faint vertical lines at 1/4 intervals (mirrors ThroughputGraphScreen)
    for (t in 1 until QualityAxisTickCount) {
        val x = size.width * t / QualityAxisTickCount
        drawLine(
            color = lineColor.copy(alpha = 0.06f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = RipDpiStroke.Hairline.toPx(),
        )
    }
}

@Composable
private fun QualityLegend(
    nowLabel: String,
    p50Label: String,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
        QualityLegendItem(
            swatchColor = colors.accent,
            label = stringResource(R.string.vpn_quality_graph_legend_now),
            value = nowLabel,
        )
        QualityLegendItem(
            swatchColor = colors.accent,
            label = stringResource(R.string.vpn_quality_graph_legend_p50),
            value = p50Label,
        )
    }
}

@Composable
private fun QualityLegendItem(
    swatchColor: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Canvas(modifier = Modifier.size(width = spacing.md, height = RipDpiStroke.Thick)) {
            drawRect(color = swatchColor)
        }
        Text(text = label, style = type.monoSmall, color = colors.mutedForeground)
        Text(text = value, style = type.monoSmall, color = colors.foreground)
    }
}
