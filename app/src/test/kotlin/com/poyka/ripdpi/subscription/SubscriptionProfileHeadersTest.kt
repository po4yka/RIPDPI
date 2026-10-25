package com.poyka.ripdpi.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class SubscriptionProfileHeadersTest {
    @Test
    fun `profile title is decoded, stripped of control characters, and bounded`() {
        assertEquals("Fleet", profileTitleOf("  Fleet "))
        assertEquals("Флот", profileTitleOf("base64:" + Base64.getEncoder().encodeToString("Флот".toByteArray())))
        assertEquals("ab", profileTitleOf("a\u0000\nb"))
        assertEquals(64, profileTitleOf("x".repeat(200)).length)
        assertEquals("", profileTitleOf("base64:%%%"))
        assertEquals("", profileTitleOf(null))
    }

    @Test
    fun `update interval accepts whole hours up to thirty days`() {
        assertEquals(12L * 60L, updateIntervalMinutesOf(" 12 "))
        assertNull(updateIntervalMinutesOf("0"))
        assertNull(updateIntervalMinutesOf("721"))
        assertNull(updateIntervalMinutesOf("1.5"))
        assertNull(updateIntervalMinutesOf(null))
    }
}
