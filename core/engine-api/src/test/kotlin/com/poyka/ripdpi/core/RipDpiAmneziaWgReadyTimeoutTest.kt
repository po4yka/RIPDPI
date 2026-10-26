package com.poyka.ripdpi.core

import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiAmneziaWgReadyTimeoutTest {
    @Test
    fun `default readiness deadline outlasts a WireGuard handshake retransmission`() {
        // WireGuard resends an unanswered initiation only after REKEY_TIMEOUT (5 s, boringtun timers.rs).
        // The deadline must also cover the answer to that resend, so one lost datagram is recoverable.
        val rekeyTimeoutMs = 5_000L
        assertTrue(defaultAmneziaWgReadyTimeoutMs >= 2 * rekeyTimeoutMs + 1_000L)
    }
}
