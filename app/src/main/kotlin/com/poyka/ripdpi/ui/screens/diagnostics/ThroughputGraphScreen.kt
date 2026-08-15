package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val MaxGraphSamples = 60
private const val AxisTickCount = 4

/**
 * Presentation-only throughput graph matching
 * `docs/design/rds/preview/vpn-throughput-graph.html`.
 *
 * Renders a dual-series Canvas line plot (download = foreground,
 * upload = info) with a fill under the download curve, time-range
 * selector chips, per-sample axis ticks, a three-stat summary grid
 * (down-now / up-now / session-total), and a legend row. All visual
 * values come from [ThroughputGraphState]; no live data access.
 * Tokens read exclusively from [RipDpiThemeTokens] and [RipDpiStroke].
 */
@Composable
fun ThroughputGraphScreen(
    state: ThroughputGraphState,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    RipDpiCard(modifier = modifier.fillMaxWidth(), variant = RipDpiCardVariant.Outlined) {
        ThroughputHeader(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        ThroughputPlot(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        ThroughputSummaryGrid(state)
        Spacer(modifier = Modifier.height(spacing.sm))
        ThroughputLegend(state)
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.vpn_throughput_graph_caption),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}

@Composable
private fun ThroughputHeader(state: ThroughputGraphState) {
    val spacing = RipDpiThemeTokens.spacing
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
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            state.rangeLabels.forEachIndexed { index, label ->
                ThroughputRangeChip(
                    label = label,
                    selected = index == state.selectedRangeIndex,
                )
            }
        }
    }
}

@Composable
private fun ThroughputRangeChip(
    label: String,
    selected: Boolean,
) {
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.components.shapes
    val chipBg = if (selected) colors.foreground else Color.Transparent
    val chipFg = if (selected) colors.background else colors.mutedForeground
    val borderColor = if (selected) colors.foreground else colors.border
    val spacing = RipDpiThemeTokens.spacing
    Canvas(
        modifier =
            Modifier
                .clip(
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(shapes.pillCornerRadius),
                ).size(width = spacing.section + spacing.xs, height = spacing.xxl),
    ) {
        drawRoundRect(
            color = chipBg,
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(shapes.pillCornerRadius.toPx()),
        )
        drawRoundRect(
            color = borderColor,
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(shapes.pillCornerRadius.toPx()),
            style = Stroke(width = RipDpiStroke.Thin.toPx()),
        )
    }
    // Overlay the label — compose text on top is simpler than drawText in Canvas
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .clip(
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(shapes.pillCornerRadius),
                ).size(width = spacing.section + spacing.xs, height = spacing.xxl)
                .then(
                    if (selected) {
                        Modifier
                    } else {
                        Modifier
                    },
                ).padding(horizontal = spacing.sm, vertical = spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.monoSmall,
            color = chipFg,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThroughputPlot(state: ThroughputGraphState) {
    val spacing = RipDpiThemeTokens.spacing
    val shapes = RipDpiThemeTokens.components.shapes
    val colors = RipDpiThemeTokens.colors
    val bgColor = colors.inputBackground
    val downColor = colors.foreground
    val upColor = colors.info
    val downStroke = RipDpiStroke.Thick
    val upStroke = RipDpiStroke.Thin
    val plotHeight = spacing.section + spacing.xxxl + spacing.xxl + spacing.xs // 100 dp
    val plotDescription = state.title

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
        drawRect(color = bgColor)
        drawThroughputCanvas(
            downloadSamples = state.downloadSamples,
            uploadSamples = state.uploadSamples,
            downColor = downColor,
            upColor = upColor,
            downStroke = downStroke,
            upStroke = upStroke,
        )
    }
}

private fun DrawScope.drawThroughputCanvas(
    downloadSamples: List<Float>,
    uploadSamples: List<Float>,
    downColor: Color,
    upColor: Color,
    downStroke: Dp,
    upStroke: Dp,
) {
    if (downloadSamples.size < 2) return
    val window =
        if (downloadSamples.size > MaxGraphSamples) {
            downloadSamples.subList(downloadSamples.size - MaxGraphSamples, downloadSamples.size)
        } else {
            downloadSamples
        }
    val upWindow =
        if (uploadSamples.size > MaxGraphSamples) {
            uploadSamples.subList(uploadSamples.size - MaxGraphSamples, uploadSamples.size)
        } else {
            uploadSamples
        }
    val maxDown = window.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val xStep = size.width / (window.size - 1).toFloat()
    // Download fill — 8% opacity under the curve
    val fillPath = Path()
    window.forEachIndexed { i, v ->
        val x = i * xStep
        val y = size.height - (v / maxDown) * size.height
        if (i == 0) fillPath.moveTo(x, y) else fillPath.lineTo(x, y)
    }
    fillPath.lineTo(size.width, size.height)
    fillPath.lineTo(0f, size.height)
    fillPath.close()
    drawPath(path = fillPath, color = downColor.copy(alpha = 0.08f))
    // Download line
    val downPath = Path()
    window.forEachIndexed { i, v ->
        val x = i * xStep
        val y = size.height - (v / maxDown) * size.height
        if (i == 0) downPath.moveTo(x, y) else downPath.lineTo(x, y)
    }
    drawPath(
        downPath,
        downColor,
        style = Stroke(width = downStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Upload line (same y-scale)
    if (upWindow.size >= 2) {
        val upXStep = size.width / (upWindow.size - 1).toFloat()
        val upPath = Path()
        upWindow.forEachIndexed { i, v ->
            val x = i * upXStep
            val y = size.height - (v / maxDown) * size.height
            if (i == 0) upPath.moveTo(x, y) else upPath.lineTo(x, y)
        }
        drawPath(
            upPath,
            upColor,
            style = Stroke(width = upStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    // Axis tick marks — faint vertical lines at 1/4 intervals
    for (t in 1 until AxisTickCount) {
        val x = size.width * t / AxisTickCount
        drawLine(
            color = downColor.copy(alpha = 0.06f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = RipDpiStroke.Hairline.toPx(),
        )
    }
}

@Composable
private fun ThroughputSummaryGrid(state: ThroughputGraphState) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        ThroughputStatCell(
            label = stringResource(R.string.vpn_throughput_graph_stat_down_label),
            value = state.downNowLabel,
            modifier = Modifier.weight(1f),
        )
        ThroughputStatCell(
            label = stringResource(R.string.vpn_throughput_graph_stat_up_label),
            value = state.upNowLabel,
            modifier = Modifier.weight(1f),
        )
        ThroughputStatCell(
            label = stringResource(R.string.vpn_throughput_graph_stat_session_label),
            value = state.sessionTotalLabel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ThroughputStatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    val shapes = RipDpiThemeTokens.components.shapes
    Canvas(
        modifier =
            modifier
                .height(spacing.section + spacing.sm),
    ) {
        drawRoundRect(
            color = colors.inputBackground,
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(shapes.mediumCornerRadius.toPx()),
            size = Size(size.width, size.height),
        )
    }
    Column(
        modifier =
            modifier
                .clip(
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(shapes.mediumCornerRadius),
                ).then(Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs)),
    ) {
        Text(
            text = label.uppercase(),
            style = type.monoSmall,
            color = colors.mutedForeground,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = value,
            style = type.monoValue,
            color = colors.foreground,
        )
    }
}

@Composable
private fun ThroughputLegend(state: ThroughputGraphState) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
        ThroughputLegendItem(
            swatchColor = colors.foreground,
            label = stringResource(R.string.vpn_throughput_graph_legend_down),
            value = state.downNowLabel,
        )
        ThroughputLegendItem(
            swatchColor = colors.info,
            label = stringResource(R.string.vpn_throughput_graph_legend_up),
            value = state.upNowLabel,
        )
    }
}

@Composable
private fun ThroughputLegendItem(
    swatchColor: Color,
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

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

/**
 * Presentation state for [ThroughputGraphScreen].
 *
 * @param title Screen / card header text (e.g. "Throughput").
 * @param rangeLabels Time-range selector labels in order (e.g. 1m, 5m, 30m, 1h).
 * @param selectedRangeIndex Index into [rangeLabels] for the active chip.
 * @param downloadSamples Normalised download bandwidth samples, latest last (raw bytes/s or MiB/s).
 * @param uploadSamples Normalised upload bandwidth samples, latest last, same unit as [downloadSamples].
 * @param downNowLabel Formatted current download rate (e.g. "1.42 MiB/s").
 * @param upNowLabel Formatted current upload rate (e.g. "312 KiB/s").
 * @param sessionTotalLabel Formatted cumulative session download (e.g. "↓ 128 MiB").
 */
data class ThroughputGraphState(
    val title: String,
    val rangeLabels: ImmutableList<String>,
    val selectedRangeIndex: Int,
    val downloadSamples: ImmutableList<Float>,
    val uploadSamples: ImmutableList<Float>,
    val downNowLabel: String,
    val upNowLabel: String,
    val sessionTotalLabel: String,
)
