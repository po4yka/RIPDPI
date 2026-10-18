package com.poyka.ripdpi.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.service.telemetry.freshness
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens

@Composable
internal fun LatencyDetailContent(
    snapshot: LatencyDetailSnapshot,
    now: Long = measurementNow(snapshot.source),
) {
    val quality = snapshot.quality
    Column(
        modifier = Modifier.fillMaxWidth().ripDpiTestTag(RipDpiTestTags.LatencyDetailValues),
        verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.sm),
    ) {
        LatencyValue(stringResource(R.string.measurement_quality_rtt, quality.rttP50Ms))
        LatencyValue(stringResource(R.string.measurement_quality_p95, quality.rttP95Ms))
        LatencyValue(
            stringResource(
                R.string.measurement_quality_samples,
                quality.sampleCount,
                quality.jitterMs.toString(),
            ),
        )
        MeasurementCaption(stringResource(R.string.latency_detail_percentiles_note))
        MeasurementCaption(
            if (quality.windowStartAtMs > 0L) {
                stringResource(R.string.measurement_quality_window, measurementTimestamp(quality.windowStartAtMs))
            } else {
                stringResource(R.string.measurement_window_unknown)
            },
        )
        LatencyDetailSource(snapshot, now)
    }
}

@Composable
private fun LatencyValue(value: String) {
    Text(
        text = value,
        modifier = Modifier.fillMaxWidth(),
        style = RipDpiThemeTokens.type.bodyEmphasis,
        color = RipDpiThemeTokens.colors.foreground,
    )
}

@Composable
private fun LatencyDetailSource(
    snapshot: LatencyDetailSnapshot,
    now: Long,
) {
    val source = snapshot.source
    if (source == null) MeasurementCaption(stringResource(R.string.latency_detail_source_unavailable))
    if (!snapshot.connected && source != null && source.freshness(now) == RuntimeMeasurementFreshness.Current) {
        // The source describes an earlier running service. Do not call it current
        // when Home was already disconnected at the click boundary.
        HistoricalLatencySource(source)
    } else {
        MeasurementSource(source, now)
    }
    if (!snapshot.connected) MeasurementCaption(stringResource(R.string.home_quality_last_values))
}

@Composable
private fun HistoricalLatencySource(source: RuntimeMeasurementSource) {
    val name =
        when (source.source) {
            "proxy" -> stringResource(R.string.measurement_proxy)
            "tunnel" -> stringResource(R.string.measurement_tunnel)
            else -> source.source
        }
    MeasurementCaption(stringResource(R.string.measurement_source, name))
    if (source.capturedAt > 0L) {
        MeasurementCaption(stringResource(R.string.measurement_snapshot, measurementTimestamp(source.capturedAt)))
    }
}
