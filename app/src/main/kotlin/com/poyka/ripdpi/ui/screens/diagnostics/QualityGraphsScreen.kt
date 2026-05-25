package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.ui.components.cards.RipDpiCard
import com.poyka.ripdpi.ui.components.cards.RipDpiCardVariant
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

/**
 * P5.7 of G008. Throughput + latency graph cells over a rolling
 * sample window. Pixel-accurate plotting deferred — this scaffold
 * locks the data contract so the graph components can be drawn once
 * the chart-library decision lands.
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
        )
        QualityGraphCard(
            title = stringResource(R.string.vpn_quality_graph_latency_title),
            currentValue = samples.lastOrNull()?.let { "${it.jitterMs} ms jitter" } ?: "—",
            sampleCount = samples.size,
        )
    }
}

@Composable
private fun QualityGraphCard(
    title: String,
    currentValue: String,
    sampleCount: Int,
) {
    val spacing = RipDpiThemeTokens.spacing
    val type = RipDpiThemeTokens.type
    val colors = RipDpiThemeTokens.colors
    RipDpiCard(variant = RipDpiCardVariant.Outlined) {
        Text(text = title, style = type.sectionTitle, color = colors.foreground)
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
