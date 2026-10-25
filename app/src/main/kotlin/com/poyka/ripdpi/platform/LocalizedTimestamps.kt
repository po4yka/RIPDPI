package com.poyka.ripdpi.platform

import android.content.Context
import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

// Skeletons for formatLocalizedTimestamp. `j` is the hour; it follows the device 12/24-hour setting.
internal const val TimestampDateTime = "MMMdjmm"
internal const val TimestampDateTimeSeconds = "MMMdjmmss"
internal const val TimestampYearDateTime = "yMMMdjmm"
internal const val TimestampYearDateTimeSeconds = "yMMMdjmmss"
internal const val TimestampTime = "jmm"
internal const val TimestampTimeSeconds = "jmmss"
internal const val TimestampTimeMillis = "jmmssSSS"

/** [TimestampDateTimeSeconds], with the year added when [timestampMillis] is not in the current year of [zoneId]. */
internal fun dateTimeSecondsSkeleton(
    timestampMillis: Long,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    fun year(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zoneId).year
    return if (year(timestampMillis) == year(nowMillis)) TimestampDateTimeSeconds else TimestampYearDateTimeSeconds
}

/** Formats a user-visible timestamp in the field order and hour cycle of [locale] and the device clock. */
internal fun formatLocalizedTimestamp(
    timestampMillis: Long,
    locale: Locale,
    is24Hour: Boolean,
    skeleton: String = TimestampDateTime,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton.replace('j', if (is24Hour) 'H' else 'h'))
    // ICU, not java.time: the pattern comes from ICU and may use letters java.time rejects.
    return SimpleDateFormat(pattern, locale)
        .apply { timeZone = TimeZone.getTimeZone(zoneId.id) }
        .format(timestampMillis)
}

/** [formatLocalizedTimestamp] with the composition's app locale and the device 12/24-hour setting. */
@Composable
internal fun localizedTimestamp(
    timestampMillis: Long,
    skeleton: String = TimestampDateTime,
): String =
    formatLocalizedTimestamp(
        timestampMillis = timestampMillis,
        locale = LocalConfiguration.current.locales[0],
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        skeleton = skeleton,
    )

/** [formatLocalizedTimestamp] for code without a composition: the app language and the device 12/24-hour setting. */
open class LocalizedTimestampFormatter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        open fun format(
            timestampMillis: Long,
            skeleton: String,
        ): String = snapshot().format(timestampMillis, skeleton)

        /**
         * Reads the app language and the 12/24-hour setting once, for a batch of timestamps. On API 32 and
         * below, reading the app language creates a configuration context, so do not repeat it per item.
         */
        fun snapshot(): Snapshot =
            Snapshot(
                locale =
                    ContextCompat
                        .getContextForLanguage(context)
                        .resources.configuration.locales[0],
                is24Hour = DateFormat.is24HourFormat(context),
            )

        class Snapshot internal constructor(
            private val locale: Locale,
            private val is24Hour: Boolean,
        ) {
            fun format(
                timestampMillis: Long,
                skeleton: String,
            ): String = formatLocalizedTimestamp(timestampMillis, locale, is24Hour, skeleton)
        }
    }
