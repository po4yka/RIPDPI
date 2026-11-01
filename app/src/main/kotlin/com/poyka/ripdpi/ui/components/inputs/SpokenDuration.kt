package com.poyka.ripdpi.ui.components.inputs

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale
import kotlin.time.Duration

private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 3600L

/**
 * A duration as the platform locale says it aloud ("1 hour, 2 minutes"), for screen readers. MeasureFormat only takes
 * varargs; the array has at most three elements.
 */
@Suppress("SpreadOperator")
internal fun spokenDuration(
    duration: Duration,
    locale: Locale,
): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0L)
    val parts =
        listOf(
            Measure(total / SecondsPerHour, MeasureUnit.HOUR),
            Measure((total % SecondsPerHour) / SecondsPerMinute, MeasureUnit.MINUTE),
            Measure(total % SecondsPerMinute, MeasureUnit.SECOND),
        ).filter { it.number.toLong() > 0L }
    return MeasureFormat
        .getInstance(locale, MeasureFormat.FormatWidth.WIDE)
        .formatMeasures(*parts.ifEmpty { listOf(Measure(0L, MeasureUnit.SECOND)) }.toTypedArray())
}
