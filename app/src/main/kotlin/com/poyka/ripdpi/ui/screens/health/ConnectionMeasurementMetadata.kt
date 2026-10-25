package com.poyka.ripdpi.ui.screens.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.dateTimeSecondsSkeleton
import com.poyka.ripdpi.platform.localizedTimestamp
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetrySamplingPolicy
import com.poyka.ripdpi.service.telemetry.freshness
import com.poyka.ripdpi.ui.components.feedback.AdvancedSection
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleDataBadge
import com.poyka.ripdpi.ui.components.indicators.liveStaleBadgeTier
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun MeasurementCaption(text: String) {
    Text(
        text = text,
        style = RipDpiThemeTokens.type.caption,
        color = RipDpiThemeTokens.colors.mutedForeground,
    )
}

@Composable
internal fun measurementTimestamp(timestamp: Long): String =
    localizedTimestamp(timestamp, dateTimeSecondsSkeleton(timestamp, nowMillis = System.currentTimeMillis()))

@Composable
internal fun measurementNow(source: RuntimeMeasurementSource?): Long {
    val now by produceState(initialValue = System.currentTimeMillis(), source) {
        while (true) {
            value = System.currentTimeMillis()
            delay(RuntimeTelemetrySamplingPolicy.InteractiveIntervalMillis)
        }
    }
    return now
}

@Composable
internal fun MeasurementSource(
    source: RuntimeMeasurementSource?,
    now: Long = measurementNow(source),
) {
    source?.let {
        val name =
            when (it.source) {
                "proxy" -> stringResource(R.string.measurement_proxy)
                "tunnel" -> stringResource(R.string.measurement_tunnel)
                else -> it.source
            }
        MeasurementCaption(stringResource(R.string.measurement_source, name))
        if (it.capturedAt > 0L) {
            MeasurementCaption(stringResource(R.string.measurement_snapshot, measurementTimestamp(it.capturedAt)))
        }
    }
    val freshness = source?.freshness(now) ?: RuntimeMeasurementFreshness.Unknown
    val label = stringResource(freshness.labelResource())
    val tier =
        source?.takeIf { freshness == RuntimeMeasurementFreshness.Stale }?.let {
            liveStaleBadgeTier(
                (now - it.capturedAt).coerceAtLeast(0L).milliseconds,
                RuntimeTelemetrySamplingPolicy.BackgroundIntervalMillis.milliseconds,
            )
        }
    if (tier != null) RipDpiStaleDataBadge(label = label, tier = tier) else MeasurementCaption(label)
}

/** Collapses explanatory captions behind one "About these numbers" toggle. */
@Composable
internal fun MeasurementHelp(vararg lines: String) {
    AdvancedSection(title = stringResource(R.string.connection_health_measurement_help_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(RipDpiThemeTokens.spacing.xs)) {
            lines.forEach { MeasurementCaption(it) }
        }
    }
}

private fun RuntimeMeasurementFreshness.labelResource(): Int =
    when (this) {
        RuntimeMeasurementFreshness.Unknown -> R.string.measurement_unknown
        RuntimeMeasurementFreshness.Stopped -> R.string.measurement_stopped
        RuntimeMeasurementFreshness.Error -> R.string.measurement_error
        RuntimeMeasurementFreshness.Current -> R.string.measurement_current
        RuntimeMeasurementFreshness.Stale -> R.string.measurement_stale
    }
