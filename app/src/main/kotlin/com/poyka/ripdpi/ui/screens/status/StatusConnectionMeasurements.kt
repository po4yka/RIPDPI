package com.poyka.ripdpi.ui.screens.status

import android.text.format.Formatter
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.service.telemetry.freshness
import com.poyka.ripdpi.services.hasObservedMeasurements
import com.poyka.ripdpi.ui.components.feedback.RipDpiMeasurementAction
import com.poyka.ripdpi.ui.components.feedback.RipDpiMeasurementMetric
import com.poyka.ripdpi.ui.components.feedback.RipDpiMeasurementStrip
import com.poyka.ripdpi.ui.components.feedback.RipDpiMeasurementTone
import com.poyka.ripdpi.ui.screens.health.LatencyDetailDialog
import com.poyka.ripdpi.ui.screens.health.LatencyDetailSnapshot
import com.poyka.ripdpi.ui.screens.health.measurementNow
import com.poyka.ripdpi.ui.screens.health.selectedLatencyDetailSnapshot
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.testing.ripDpiTestTag
import com.poyka.ripdpi.ui.theme.DefaultRipDpiNetworkQualityThresholds
import com.poyka.ripdpi.ui.theme.resolveMeasurementTone
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.first

@Composable
internal fun StatusConnectionMeasurements(
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
    val qualityMeaning = stringResource(R.string.measurement_quality_meaning)
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
    val summary = resolveStatusMeasurementSummary(observed, source?.freshness(currentTime).takeIf { connected })
    if (hasMeasurements) {
        RipDpiMeasurementStrip(
            title = stringResource(summary.title),
            body = stringResource(summary.body),
            metrics =
                statusMeasurementMetrics(
                    observed,
                    dataTransferred,
                    latencyModifier =
                        Modifier
                            .semantics { stateDescription = qualityMeaning }
                            .focusProperties { canFocus = true }
                            .focusRequester(
                                latencyFocus,
                            ).ripDpiTestTag(RipDpiTestTags.StatusLatencyMetric),
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
            footnote =
                observed?.takeIf { it.sampleCount > 0L }?.let {
                    pluralStringResource(R.plurals.status_quality_samples, it.sampleCount.toInt(), it.sampleCount)
                },
            primaryAction = measureAction(measured = observed != null, onMeasure = onReprobe),
            secondaryAction = null,
            tone = summary.tone,
            modifier = Modifier.fillMaxWidth(),
        )
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
) = RipDpiMeasurementAction(
    stringResource(if (measured) R.string.vpn_quality_strip_reprobe else R.string.status_quality_measure),
    onMeasure,
)

internal data class StatusMeasurementSummary(
    val title: Int = R.string.connection_health_summary_title,
    val body: Int,
    val tone: RipDpiMeasurementTone = RipDpiMeasurementTone.Nominal,
)

internal fun resolveStatusMeasurementSummary(
    quality: ConnectionQualitySnapshot?,
    freshness: RuntimeMeasurementFreshness?,
): StatusMeasurementSummary =
    when {
        quality?.hasObservedMeasurements() != true -> {
            StatusMeasurementSummary(body = R.string.status_quality_waiting)
        }

        freshness != RuntimeMeasurementFreshness.Current -> {
            StatusMeasurementSummary(body = R.string.status_quality_last_values)
        }

        quality.sampleCount < DefaultRipDpiNetworkQualityThresholds.minSampleCountForVerdict -> {
            StatusMeasurementSummary(body = R.string.status_quality_partial)
        }

        else -> {
            assessedStatusMeasurementSummary(quality)
        }
    }

private fun assessedStatusMeasurementSummary(quality: ConnectionQualitySnapshot): StatusMeasurementSummary =
    when (resolveMeasurementTone(quality)) {
        RipDpiMeasurementTone.Warning -> {
            StatusMeasurementSummary(
                R.string.vpn_quality_strip_warning_title,
                R.string.vpn_quality_strip_body_warning,
                RipDpiMeasurementTone.Warning,
            )
        }

        RipDpiMeasurementTone.Critical -> {
            StatusMeasurementSummary(
                R.string.vpn_quality_strip_critical_title,
                R.string.vpn_quality_strip_body_critical,
                RipDpiMeasurementTone.Critical,
            )
        }

        else -> {
            StatusMeasurementSummary(R.string.vpn_quality_strip_nominal_title, R.string.vpn_quality_strip_body_nominal)
        }
    }

@Composable
private fun statusMeasurementMetrics(
    quality: ConnectionQualitySnapshot?,
    dataTransferred: Long,
    latencyModifier: Modifier,
    onOpenLatency: () -> Unit,
) = buildList {
    quality?.let {
        // Zero loss with no samples is an empty window, not a good result.
        if (it.lossPct > 0f || it.sampleCount > 0L) {
            add(
                statusMetric(
                    R.string.vpn_quality_metric_loss,
                    stringResource(R.string.status_quality_metric_loss_format, it.lossPct),
                ),
            )
        }
        if (it.sampleCount > 0L) {
            add(
                statusMetric(
                    R.string.vpn_quality_metric_rtt_p50,
                    stringResource(R.string.status_quality_metric_ms_format, it.rttP50Ms),
                    action = RipDpiMeasurementAction(stringResource(R.string.latency_detail_open), onOpenLatency),
                    modifier = latencyModifier,
                ),
            )
            add(
                statusMetric(
                    R.string.vpn_quality_metric_jitter,
                    stringResource(R.string.status_quality_metric_ms_format, it.jitterMs),
                ),
            )
        }
    }
    add(
        statusMetric(
            R.string.status_quality_traffic_total,
            Formatter.formatShortFileSize(LocalContext.current, dataTransferred),
        ),
    )
}.toImmutableList()

@Composable
private fun statusMetric(
    label: Int,
    value: String,
    action: RipDpiMeasurementAction? = null,
    modifier: Modifier = Modifier,
) = RipDpiMeasurementMetric(
    stringResource(label),
    value,
    delta = "",
    deltaIsBad = false,
    action = action,
    modifier = modifier,
)
