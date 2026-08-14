package com.poyka.ripdpi.ui.screens.tuner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.RankedStrategyProbeResult
import com.poyka.ripdpi.ui.components.buttons.RipDpiButton
import com.poyka.ripdpi.ui.components.buttons.RipDpiButtonVariant
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricPill
import com.poyka.ripdpi.ui.components.indicators.RipDpiMetricTone
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

private const val PercentScale = 100

@Composable
internal fun StrategyTunerRankedRow(
    result: RankedStrategyProbeResult,
    isBest: Boolean,
    isApplied: Boolean,
    applyEnabled: Boolean,
    onApply: () -> Unit,
) {
    val spacing = RipDpiThemeTokens.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.strategyLabel,
                style = RipDpiThemeTokens.type.bodyEmphasis,
                color = RipDpiThemeTokens.colors.foreground,
            )
            Text(
                text =
                    stringResource(
                        R.string.strategy_tuner_result_detail_format,
                        result.successes,
                        result.total,
                        result.averageLatencyMs,
                    ),
                style = RipDpiThemeTokens.type.caption,
                color = RipDpiThemeTokens.colors.mutedForeground,
            )
        }
        RipDpiMetricPill(
            text =
                if (isBest) {
                    stringResource(R.string.strategy_tuner_best_badge)
                } else {
                    stringResource(
                        R.string.strategy_tuner_rate_format,
                        (result.successRate * PercentScale).toInt(),
                    )
                },
            tone = if (isBest) RipDpiMetricTone.Positive else RipDpiMetricTone.Neutral,
        )
        RipDpiButton(
            text =
                if (isApplied) {
                    stringResource(R.string.strategy_tuner_applied)
                } else {
                    stringResource(R.string.strategy_tuner_apply)
                },
            onClick = onApply,
            enabled = applyEnabled && !isApplied,
            variant = RipDpiButtonVariant.Secondary,
        )
    }
}
