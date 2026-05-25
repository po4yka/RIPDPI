package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiStroke
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val MaxPlotSamples = 60

/**
 * P5.7 of G008. Throughput + latency graph cells over a rolling
 * sample window. Canvas-based line plots render above each card's
 * current-value text; no external chart library is used.
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
            currentValue = samples.lastOrNull()?.let { "${it.rttP50Ms} ms p50" } ?: "—",
            sampleCount = samples.size,
            samples = samples,
            selector = { it.rttP50Ms },
        )
        QualityGraphCard(
            title = stringResource(R.string.vpn_quality_graph_latency_title),
            currentValue = samples.lastOrNull()?.let { "${it.jitterMs} ms jitter" } ?: "—",
            sampleCount = samples.size,
            samples = samples,
            selector = { it.jitterMs },
        )
    }
}

@Composable
private fun QualityGraphCard(
    title: String,
    currentValue: String,
    sampleCount: Int,
    samples: ImmutableList<ConnectionQualitySnapshot>,
    selector: (ConnectionQualitySnapshot) -> Long,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(text = title, style = type.sectionTitle, color = colors.foreground)
        Spacer(modifier = Modifier.height(spacing.sm))
        // Canvas line plot — section(40) + xxxl(32) + xxl(24) + xs(4) = 100 dp
        QualityLinePlot(
            samples = samples,
            selector = selector,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(spacing.section + spacing.xxxl + spacing.xxl + spacing.xs),
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(text = currentValue, style = type.monoValue, color = colors.foreground)
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.vpn_quality_graph_samples_format, sampleCount),
            style = type.caption,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun QualityLinePlot(
    samples: ImmutableList<ConnectionQualitySnapshot>,
    selector: (ConnectionQualitySnapshot) -> Long,
    modifier: Modifier = Modifier,
) {
    val colors = RipDpiThemeTokens.colors
    val bgColor = colors.inputBackground
    val lineColor = colors.accent
    val strokeWidthDp = RipDpiStroke.Thick

    val window =
        if (samples.size > MaxPlotSamples) {
            samples.subList(samples.size - MaxPlotSamples, samples.size)
        } else {
            samples
        }
    val values = window.map { selector(it) }

    Canvas(modifier = modifier) {
        drawRect(color = bgColor)

        if (values.size < 2) return@Canvas

        val maxValue = values.max().coerceAtLeast(1L).toFloat()
        val xStep = size.width / (values.size - 1).toFloat()

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * xStep
            val y = size.height - (value.toFloat() / maxValue) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style =
                Stroke(
                    width = strokeWidthDp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
        )
    }
}
