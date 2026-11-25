package com.poyka.ripdpi.core

import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiAmneziaWgReadyTimeoutTest {
    @Test
    fun `default readiness deadline outlasts a WireGuard handshake retransmission`() {
        val rekeyTimeoutMs = 5_000L
        assertTrue(defaultAmneziaWgReadyTimeoutMs >= 2 * rekeyTimeoutMs + 1_000L)
    }
}
