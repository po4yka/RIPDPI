package com.poyka.ripdpi.data.subscription

import com.poyka.ripdpi.data.ProxyProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPayloadLimitsTest {
    @Test
    fun `exact payload limit is accepted`() {
        val body = "x".repeat(MaxSubscriptionPayloadBytes.toInt()).toResponseBody("text/plain".toMediaType())

        assertEquals(MaxSubscriptionPayloadBytes.toInt(), body.readBoundedSubscriptionPayload().text.length)
    }

    @Test
    fun `migration hash retains exact bytes and rejects lossy text decoding`() {
        val bytes = "  {\"key\":\"значение\"} ".toByteArray(Charsets.UTF_8)
        val payload =
            bytes
                .toResponseBody("application/json; charset=utf-8".toMediaType())
                .readBoundedSubscriptionPayload()
        payload.requireMigrationUtf8()
        assertEquals(migrationSha256(bytes), payload.sha256)
        val invalid =
            byteArrayOf(0xC3.toByte(), 0x28)
                .toResponseBody("application/json".toMediaType())
                .readBoundedSubscriptionPayload()
        assertTrue(runCatching { invalid.requireMigrationUtf8() }.exceptionOrNull() is IllegalArgumentException)
        val differentCharset =
            bytes
                .toResponseBody("application/json; charset=iso-8859-1".toMediaType())
                .readBoundedSubscriptionPayload()
        assertTrue(
            runCatching { differentCharset.requireMigrationUtf8() }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `raw config and profile expansion limits are enforced`() {
        val bounded = rawProfile("x".repeat(MaxSubscriptionRawConfigBytes))
        val oversized = rawProfile("x".repeat(MaxSubscriptionRawConfigBytes + 1))

        assertTrue(listOf(bounded).fitsSubscriptionLimits())
        assertFalse(listOf(oversized).fitsSubscriptionLimits())
        assertFalse(List(MaxSubscriptionProfiles + 1) { bounded }.fitsSubscriptionLimits())
    }

    private fun rawProfile(config: String): ProxyProfile.RawConfig =
        ProxyProfile.RawConfig(
            id = "raw",
            displayName = "Raw",
            groupId = "group",
            config = config,
        )
}
