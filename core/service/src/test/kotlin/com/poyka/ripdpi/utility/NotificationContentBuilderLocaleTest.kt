package com.poyka.ripdpi.utility

import android.content.res.Configuration
import org.junit.Assert.assertEquals
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
    @Config(qualifiers = "en")
    fun `traffic decimals use Spanish context on English system and follow context updates`() {
        val application = RuntimeEnvironment.getApplication()
        val originalLocale = Locale.getDefault()
        val configuration = Configuration(application.resources.configuration)
        try {
            Locale.setDefault(Locale.ENGLISH)
            RuntimeEnvironment.setQualifiers("+es")
            assertEquals(
                "↑ 1,5 MB  ↓ 1,5 GB",
                NotificationContentBuilder.buildContentText(application, 1_500_000L, 1_500_000_000L),
            )

            RuntimeEnvironment.setQualifiers("+en")
            assertEquals(
                "↑ 1.5 MB  ↓ 1.5 GB",
                NotificationContentBuilder.buildContentText(application, 1_500_000L, 1_500_000_000L),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @Config(qualifiers = "es")
    fun `traffic unit boundaries remain stable in Spanish`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("↑ 0 B  ↓ 999 B", NotificationContentBuilder.buildContentText(context, 0L, 999L))
        assertEquals(
            "↑ 1,0 KB  ↓ 1,5 KB",
            NotificationContentBuilder.buildContentText(context, 1_000L, 1_500L),
        )
        assertEquals(
            "↑ 1,0 MB  ↓ 1,0 GB",
            NotificationContentBuilder.buildContentText(context, 1_000_000L, 1_000_000_000L),
        )
    }
}
