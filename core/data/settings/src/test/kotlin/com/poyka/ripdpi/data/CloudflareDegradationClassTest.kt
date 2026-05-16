package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareDegradationClassTest {
    @Test
    fun `all five variants are present`() {
        val variants = CloudflareDegradationClass.entries
        assertTrue(variants.any { it == CloudflareDegradationClass.EdgeThrottling })
        assertTrue(variants.any { it == CloudflareDegradationClass.DomainBlocked })
        assertTrue(variants.any { it == CloudflareDegradationClass.OriginFailure })
        assertTrue(variants.any { it == CloudflareDegradationClass.ClientProtocolFailure })
        assertTrue(variants.any { it == CloudflareDegradationClass.WhitelistShutdown })
        assertEquals(5, variants.size)
    }

    @Test
    fun `userVisibleSummary mapping is total and returns expected strings`() {
        assertEquals(
            "degraded Cloudflare-like path",
            CloudflareDegradationClass.EdgeThrottling.userVisibleSummary(),
        )
        assertEquals(
            "degraded Cloudflare-like path",
            CloudflareDegradationClass.DomainBlocked.userVisibleSummary(),
        )
        assertEquals(
            "origin issue",
            CloudflareDegradationClass.OriginFailure.userVisibleSummary(),
        )
        assertEquals(
            "profile issue",
            CloudflareDegradationClass.ClientProtocolFailure.userVisibleSummary(),
        )
        assertEquals(
            "network restricted",
            CloudflareDegradationClass.WhitelistShutdown.userVisibleSummary(),
        )
    }

    @Test
    fun `userVisibleSummary exhaustiveness - every variant maps to a known summary`() {
        val knownSummaries =
            setOf(
                "degraded Cloudflare-like path",
                "origin issue",
                "network restricted",
                "profile issue",
            )
        CloudflareDegradationClass.entries.forEach { variant ->
            assertTrue(
                "Unmapped variant: $variant",
                variant.userVisibleSummary() in knownSummaries,
            )
        }
    }
}
