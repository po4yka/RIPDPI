package com.poyka.ripdpi.subscription

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class SubscriptionProfileHeadersTest {
    @Test
    fun `profile title is decoded, stripped of control characters, and bounded`() {
        assertEquals("Fleet", profileTitleOf("  Fleet "))
        assertEquals("Флот", profileTitleOf("base64:" + Base64.getEncoder().encodeToString("Флот".toByteArray())))
        assertEquals("ab", profileTitleOf("a\u0000\nb"))
        assertEquals("evilgood", profileTitleOf("evil\u202Egood"))
        assertEquals(63, profileTitleOf("x".repeat(63) + "\uD83D\uDE00").length)
        assertEquals(64, profileTitleOf("x".repeat(200)).length)
        assertEquals("", profileTitleOf("base64:%%%"))
        assertEquals("", profileTitleOf(null))
    }
}
