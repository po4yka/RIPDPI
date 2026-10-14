package com.poyka.ripdpi.utility

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ru")
class NotificationContentBuilderLocaleTest {
    @Test
    fun `Russian session counts use one few and many forms`() {
        val context = RuntimeEnvironment.getApplication()
        val counts = listOf(1L, 2L, 5L, 21L, 22L, 25L)
        val labels =
            listOf(
                "активная сессия",
                "активные сессии",
                "активных сессий",
                "активная сессия",
                "активные сессии",
                "активных сессий",
            )

        counts.forEachIndexed { index, count ->
            assertEquals("$count ${labels[index]}", NotificationContentBuilder.buildSubText(context, count, null))
        }
        assertEquals("2 активные сессии  ·  RTT 50ms", NotificationContentBuilder.buildSubText(context, 2, 50))
        assertEquals("RTT 50ms", NotificationContentBuilder.buildSubText(context, 0, 50))
        assertNull(NotificationContentBuilder.buildSubText(context, 0, null))
    }

    @Test
    @Config(qualifiers = "en")
    fun `English singular session keeps a singular noun`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("1 active session", NotificationContentBuilder.buildSubText(context, 1, null))
        assertEquals("2 active sessions", NotificationContentBuilder.buildSubText(context, 2, null))
    }

    @Test
    @Config(qualifiers = "en")
    fun `traffic decimals use Spanish context on English system and follow context updates`() {
        val application = RuntimeEnvironment.getApplication()
        val originalLocale = Locale.getDefault()
        val configuration = Configuration(application.resources.configuration)
        try {
            Locale.setDefault(Locale.ENGLISH)
            configuration.setLocale(Locale.forLanguageTag("es"))
            application.resources.updateConfiguration(configuration, application.resources.displayMetrics)
            assertEquals(
                "↑ 1,5 MB  ↓ 1,5 GB  |  01:01:01",
                NotificationContentBuilder.buildContentText(application, 1_500_000L, 1_500_000_000L, 3_661_000L),
            )

            configuration.setLocale(Locale.ENGLISH)
            application.resources.updateConfiguration(configuration, application.resources.displayMetrics)
            assertEquals(
                "↑ 1.5 MB  ↓ 1.5 GB  |  01:01:01",
                NotificationContentBuilder.buildContentText(application, 1_500_000L, 1_500_000_000L, 3_661_000L),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @Config(qualifiers = "es")
    fun `traffic unit boundaries and clamped duration remain stable in Spanish`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("↑ 0 B  ↓ 999 B  |  00:00:00", NotificationContentBuilder.buildContentText(context, 0L, 999L, -1L))
        assertEquals(
            "↑ 1,0 KB  ↓ 1,5 KB  |  00:01:00",
            NotificationContentBuilder.buildContentText(context, 1_000L, 1_500L, 60_000L),
        )
        assertEquals(
            "↑ 1,0 MB  ↓ 1,0 GB  |  01:00:00",
            NotificationContentBuilder.buildContentText(context, 1_000_000L, 1_000_000_000L, 3_600_000L),
        )
    }
}
