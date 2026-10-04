package com.poyka.ripdpi.ui.screens.home

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.service.telemetry.freshness
import com.poyka.ripdpi.services.hasObservedMeasurements
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationMetric
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationStrip
import com.poyka.ripdpi.ui.components.feedback.RipDpiDegradationTone
import com.poyka.ripdpi.ui.screens.health.MeasurementCaption
import com.poyka.ripdpi.ui.screens.health.MeasurementSource
import com.poyka.ripdpi.ui.screens.health.measurementNow
import com.poyka.ripdpi.ui.screens.health.measurementTimestamp
import com.poyka.ripdpi.ui.theme.DefaultRipDpiNetworkQualityThresholds
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.resolveDegradationTone
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun HomeConnectionMeasurements(
    quality: ConnectionQualitySnapshot?,
    source: RuntimeMeasurementSource?,
    connected: Boolean,
    dataTransferred: Long,
    onReprobe: () -> Unit,
    now: Long? = null,
) {
    val observed = quality?.takeIf { it.hasObservedMeasurements() }
    if (!connected && observed == null && dataTransferred == 0L) return
    val currentTime = now ?: source?.let { measurementNow(it) } ?: 0L
    val summary = resolveHomeMeasurementSummary(observed, source?.freshness(currentTime).takeIf { connected })
    Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
        RipDpiDegradationStrip(
            title = stringResource(summary.title),
            body = stringResource(summary.body),
            metrics = homeMeasurementMetrics(observed, dataTransferred),
            sinceLabel =
                observed?.takeIf { it.sampleCount > 0L }?.let {
                    stringResource(R.string.home_quality_samples, it.sampleCount)
                } ?: stringResource(R.string.measurement_byte_total),
            primaryAction = RipDpiDegradationAction(stringResource(R.string.vpn_quality_strip_reprobe), onReprobe),
            secondaryAction = null,
            tone = summary.tone,
            modifier = Modifier.fillMaxWidth(),
        )
        observed?.let {
            MeasurementCaption(stringResource(R.string.measurement_quality_meaning))
            MeasurementCaption(
                if (it.windowStartAtMs > 0L) {
                    stringResource(R.string.measurement_quality_window, measurementTimestamp(it.windowStartAtMs))
                } else {
                    stringResource(R.string.measurement_window_unknown)
                },
            )
            MeasurementSource(source, currentTime)
        }
    }
}

internal data class HomeMeasurementSummary(
    val title: Int = R.string.home_quality_measurements_title,
    val body: Int,
    val tone: RipDpiDegradationTone = RipDpiDegradationTone.Nominal,
)

internal fun resolveHomeMeasurementSummary(
    quality: ConnectionQualitySnapshot?,
    freshness: RuntimeMeasurementFreshness?,
): HomeMeasurementSummary =
    when {
        quality?.hasObservedMeasurements() != true -> {
            HomeMeasurementSummary(body = R.string.home_quality_waiting)
        }

        freshness != RuntimeMeasurementFreshness.Current -> {
            HomeMeasurementSummary(body = R.string.home_quality_last_values)
        }

        quality.sampleCount < DefaultRipDpiNetworkQualityThresholds.minSampleCountForVerdict -> {
            HomeMeasurementSummary(body = R.string.home_quality_partial)
        }

        else -> {
            assessedHomeMeasurementSummary(quality)
        }
    }

private fun assessedHomeMeasurementSummary(quality: ConnectionQualitySnapshot): HomeMeasurementSummary =
    when (resolveDegradationTone(quality)) {
        RipDpiDegradationTone.Warning -> {
            HomeMeasurementSummary(
                R.string.vpn_quality_strip_warning_title,
                R.string.vpn_quality_strip_body_warning,
                RipDpiDegradationTone.Warning,
            )
        }

        RipDpiDegradationTone.Critical -> {
            HomeMeasurementSummary(
                R.string.vpn_quality_strip_critical_title,
                R.string.vpn_quality_strip_body_critical,
                RipDpiDegradationTone.Critical,
            )
        }

        else -> {
            HomeMeasurementSummary(R.string.vpn_quality_strip_nominal_title, R.string.vpn_quality_strip_body_nominal)
        }
    }

@Composable
private fun homeMeasurementMetrics(
    quality: ConnectionQualitySnapshot?,
    dataTransferred: Long,
) = buildList {
    quality?.let {
        add(
            homeMetric(
                R.string.vpn_quality_metric_loss,
                stringResource(R.string.home_quality_metric_loss_format, it.lossPct),
            ),
        )
        if (it.sampleCount > 0L) {
            add(
                homeMetric(
                    R.string.vpn_quality_metric_rtt_p50,
                    stringResource(R.string.home_quality_metric_ms_format, it.rttP50Ms),
                ),
            )
            add(
                homeMetric(
                    R.string.vpn_quality_metric_jitter,
                    stringResource(R.string.home_quality_metric_ms_format, it.jitterMs),
                ),
            )
        }
    }
    add(
        homeMetric(
            R.string.home_quality_traffic_total,
            Formatter.formatShortFileSize(LocalContext.current, dataTransferred),
        ),
    )
}.toImmutableList()

@Composable
private fun homeMetric(
    label: Int,
    value: String,
) = RipDpiDegradationMetric(stringResource(label), value, delta = "", deltaIsBad = false)
