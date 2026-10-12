package com.poyka.ripdpi.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
}
