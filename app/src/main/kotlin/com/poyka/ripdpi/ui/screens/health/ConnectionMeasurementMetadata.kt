package com.poyka.ripdpi.ui.screens.health

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import com.poyka.ripdpi.R
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementFreshness
import com.poyka.ripdpi.service.telemetry.RuntimeMeasurementSource
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetrySamplingPolicy
import com.poyka.ripdpi.service.telemetry.freshness
import com.poyka.ripdpi.ui.components.indicators.RipDpiStaleDataBadge
import com.poyka.ripdpi.ui.components.indicators.liveStaleBadgeTier
import com.poyka.ripdpi.ui.theme.RipDpiThemeTokens
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
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
    DateFormat
        .getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, LocalLocale.current.platformLocale)
        .format(Date(timestamp))

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

private fun RuntimeMeasurementFreshness.labelResource(): Int =
    when (this) {
        RuntimeMeasurementFreshness.Unknown -> R.string.measurement_unknown
        RuntimeMeasurementFreshness.Stopped -> R.string.measurement_stopped
        RuntimeMeasurementFreshness.Error -> R.string.measurement_error
        RuntimeMeasurementFreshness.Current -> R.string.measurement_current
        RuntimeMeasurementFreshness.Stale -> R.string.measurement_stale
    }
