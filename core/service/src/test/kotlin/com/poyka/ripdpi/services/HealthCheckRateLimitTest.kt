package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckRateLimitTest {
    @Test
    fun `first check is allowed`() {
        val limiter = HealthCheckRateLimiter(cooldownMs = 60_000L)
        assertTrue(limiter.tryAcquire(nowMs = 0L))
    }

    @Test
    fun `second check within cooldown is blocked`() {
        val limiter = HealthCheckRateLimiter(cooldownMs = 60_000L)
        limiter.tryAcquire(nowMs = 0L)
        assertFalse(limiter.tryAcquire(nowMs = 30_000L))
    }

    @Test
    fun `check after cooldown expires is allowed`() {
        val limiter = HealthCheckRateLimiter(cooldownMs = 60_000L)
        limiter.tryAcquire(nowMs = 0L)
        assertTrue(limiter.tryAcquire(nowMs = 60_001L))
    }

    @Test
    fun `probe URL constructor does not accept subscription token`() {
        val url = HealthProbeUrl.build(host = "probe.example.com", path = "/health")
        assertFalse(url.rawUrl.contains("token", ignoreCase = true))
        assertFalse(url.rawUrl.contains("subscription", ignoreCase = true))
        assertFalse(url.rawUrl.contains("key", ignoreCase = true))
        assertTrue(url.rawUrl.startsWith("https://") || url.rawUrl.startsWith("http://"))
    }

    @Test
    fun `HealthProbeUrl only exposes host and path`() {
        val url = HealthProbeUrl.build(host = "probe.example.com", path = "/large")
        assertTrue(url.rawUrl.contains("probe.example.com"))
        assertTrue(url.rawUrl.contains("/large"))
    }
}
