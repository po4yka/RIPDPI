package com.poyka.ripdpi.ui.screens.home

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
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
import com.poyka.ripdpi.ui.screens.health.LatencyDetailDialog
import com.poyka.ripdpi.ui.screens.health.LatencyDetailSnapshot
import com.poyka.ripdpi.ui.screens.health.MeasurementCaption
import com.poyka.ripdpi.ui.screens.health.MeasurementSource
import com.poyka.ripdpi.ui.screens.health.measurementNow
import com.poyka.ripdpi.ui.screens.health.measurementTimestamp
import com.poyka.ripdpi.ui.screens.health.selectedLatencyDetailSnapshot
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.DefaultRipDpiNetworkQualityThresholds
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import com.poyka.ripdpi.ui.theme.resolveDegradationTone
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first

@Composable
internal fun HomeConnectionMeasurements(
    quality: ConnectionQualitySnapshot?,
    source: RuntimeMeasurementSource?,
    connected: Boolean,
    dataTransferred: Long,
    onReprobe: () -> Unit,
    now: Long? = null,
) {
    var selectedSnapshot by remember { mutableStateOf<LatencyDetailSnapshot?>(null) }
    var returnFocusRequested by remember { mutableStateOf(false) }
    val latencyFocus = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val observed = quality?.takeIf { it.hasObservedMeasurements() }
    val hasLatencyOrigin = observed != null && observed.sampleCount > 0L
    val hasMeasurements = connected || observed != null || dataTransferred != 0L
    LaunchedEffect(returnFocusRequested, hasLatencyOrigin, selectedSnapshot, windowInfo) {
        if (returnFocusRequested && selectedSnapshot == null) {
            if (hasLatencyOrigin) {
                // Dialog disposal precedes the host window's focus callback.
                snapshotFlow { windowInfo.isWindowFocused }.first { it }
                latencyFocus.requestFocus()
            }
            returnFocusRequested = false
        }
    }
    if (!hasMeasurements && selectedSnapshot == null) return
    val currentTime = now ?: source?.let { measurementNow(it) } ?: 0L
    val summary = resolveHomeMeasurementSummary(observed, source?.freshness(currentTime).takeIf { connected })
    if (hasMeasurements) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            RipDpiDegradationStrip(
                title = stringResource(summary.title),
                body = stringResource(summary.body),
                metrics =
                    homeMeasurementMetrics(
                        observed,
                        dataTransferred,
                        latencyModifier =
                            Modifier
                                .focusProperties { canFocus = true }
                                .focusRequester(
                                    latencyFocus,
                                ).ripDpiTestTag(RipDpiTestTags.HomeLatencyMetric),
                        onOpenLatency = {
                            returnFocusRequested = false
                            selectedSnapshot =
                                selectedLatencyDetailSnapshot(
                                    observed,
                                    source,
                                    connected,
                                    now ?: System.currentTimeMillis(),
                                )
                        },
                    ),
                sinceLabel =
                    observed?.takeIf { it.sampleCount > 0L }?.let {
                        pluralStringResource(R.plurals.home_quality_samples, it.sampleCount.toInt(), it.sampleCount)
                    } ?: stringResource(R.string.measurement_byte_total),
                primaryAction = measureAction(measured = observed != null, onMeasure = onReprobe),
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
    selectedSnapshot?.let { snapshot ->
        LatencyDetailDialog(snapshot) {
            selectedSnapshot = null
            returnFocusRequested = true
        }
    }
}

/** "Re-measure" only after a measurement exists; before that there is nothing to repeat. */
@Composable
private fun measureAction(
    measured: Boolean,
    onMeasure: () -> Unit,
) = RipDpiDegradationAction(
    stringResource(if (measured) R.string.vpn_quality_strip_reprobe else R.string.home_quality_measure),
    onMeasure,
)

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
    latencyModifier: Modifier,
    onOpenLatency: () -> Unit,
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
                    action = RipDpiDegradationAction(stringResource(R.string.latency_detail_open), onOpenLatency),
                    modifier = latencyModifier,
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
    action: RipDpiDegradationAction? = null,
    modifier: Modifier = Modifier,
) = RipDpiDegradationMetric(
    stringResource(label),
    value,
    delta = "",
    deltaIsBad = false,
    action = action,
    modifier = modifier,
)
