package com.poyka.ripdpi.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizedTimestampsTest {
    private val timestamp = Instant.parse("2026-10-25T13:38:50Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    private fun format(
        locale: Locale,
        is24Hour: Boolean,
        skeleton: String = TimestampDateTime,
    ): String = formatLocalizedTimestamp(timestamp, locale, is24Hour, skeleton, utc)

    @Test
    fun `date and time follow locale field order and the device clock`() {
        // CLDR en-US "MMM d, h:mm a"; Android keeps an ASCII space before the day period.
        assertEquals("Oct 25, 1:38 PM", format(Locale.US, is24Hour = false))
        assertEquals("Oct 25, 13:38", format(Locale.US, is24Hour = true))
        // CLDR: Russian puts the day before the abbreviated month.
        val russian = Locale.forLanguageTag("ru")
        assertEquals("25 окт., 13:38", format(russian, is24Hour = true))
        // CLDR ru "h:mm a" keeps its narrow no-break space before the day period.
        assertEquals("25 окт., 1:38\u202FPM", format(russian, is24Hour = false))
    }

    @Test
    fun `time-only skeletons keep seconds and the hour cycle`() {
        assertEquals("1:38:50 PM", format(Locale.US, is24Hour = false, skeleton = TimestampTimeSeconds))
        assertEquals("13:38:50", format(Locale.US, is24Hour = true, skeleton = TimestampTimeSeconds))
        assertEquals("13:38", format(Locale.forLanguageTag("ru"), is24Hour = true, skeleton = TimestampTime))
    }

    @Test
    fun `every shipped locale formats every skeleton in both hour cycles`() {
        val skeletons =
            listOf(
                TimestampDateTime,
                TimestampDateTimeSeconds,
                TimestampYearDateTime,
                TimestampTime,
                TimestampTimeSeconds,
                TimestampTimeMillis,
            )
        listOf("en", "ru", "es", "de", "fr", "fa", "ar", "zh-CN", "hi", "pt-BR").forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            skeletons.forEach { skeleton ->
                listOf(false, true).forEach { is24Hour ->
                    val text = format(locale, is24Hour, skeleton)
                    assertTrue("$tag $skeleton 24h=$is24Hour is blank", text.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `the zone shifts the rendered wall clock`() {
        val tbilisi = ZoneId.of("Asia/Tbilisi")
        assertEquals(
            "Oct 25, 17:38",
            formatLocalizedTimestamp(timestamp, Locale.US, is24Hour = true, zoneId = tbilisi),
        )
    }
}
