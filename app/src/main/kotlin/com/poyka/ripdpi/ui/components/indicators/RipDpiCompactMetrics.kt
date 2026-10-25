package com.poyka.ripdpi.ui.components.indicators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.collections.immutable.ImmutableList

private const val AccessibilityMetricFontScale = 1.5f
private const val AccessibilityMetricColumns = 2

/**
 * One row of short label/value pairs, value over a caption label. Large text wraps the pairs
 * into two columns so labels stay on whole lines.
 */
@Composable
fun RipDpiCompactMetrics(
    metrics: ImmutableList<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val spacing = RipDpiThemeTokens.spacing
    if (LocalDensity.current.fontScale >= AccessibilityMetricFontScale) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            maxItemsInEachRow = AccessibilityMetricColumns,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            metrics.forEach { (label, value) ->
                CompactMetric(label = label, value = value, modifier = Modifier.weight(1f))
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            metrics.forEach { (label, value) ->
                CompactMetric(label = label, value = value, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompactMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    Column(modifier = modifier.padding(top = RipDpiThemeTokens.spacing.xs)) {
        Text(
            text = value,
            style = RipDpiThemeTokens.type.bodyEmphasis,
            color = RipDpiThemeTokens.colors.foreground,
        )
        Text(
            text = label.uppercase(locale),
            style = RipDpiThemeTokens.type.caption,
            color = RipDpiThemeTokens.colors.mutedForeground,
        )
    }
}
