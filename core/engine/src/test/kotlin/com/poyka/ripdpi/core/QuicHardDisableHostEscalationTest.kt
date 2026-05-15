package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance criterion: Hard-disable tightens to the entire host for persistent cases.
 *
 * When any capability tuple for a host carries QuicMode.HARD_DISABLE, all other
 * tuples for the same host must be escalated to HARD_DISABLE.  Per-tuple
 * SOFT_DISABLE entries are subsumed; other hosts are unaffected; TCP/443
 * (non-UDP traffic) is not changed.
 */
class QuicHardDisableHostEscalationTest {
    // 1. HARD_DISABLE on one tuple escalates all tuples for the same host

    @Test
    fun `hard disable on one ip-set tuple escalates all tuples for the same host`() {
        val ctx =
            RipDpiRuntimeContext(
                directPathCapabilities =
                    listOf(
                        capability("example.com", "aabb", QuicMode.HARD_DISABLE),
                        capability("example.com", "ccdd", QuicMode.SOFT_DISABLE),
                        capability("example.com", "eeff", QuicMode.ALLOW),
                    ),
            )

        val normalized = normalizeRuntimeContext(ctx)

        val caps = normalized?.directPathCapabilities.orEmpty()
        assertEquals(
            "all tuples for example.com must be HARD_DISABLE",
            3,
            caps.count { it.authority == "example.com" && it.quicMode == QuicMode.HARD_DISABLE },
        )
    }

    // 2. SOFT_DISABLE entries for same host are subsumed (no double-counting)

    @Test
    fun `soft disable entries are subsumed when host has a hard disable tuple`() {
        val ctx =
            RipDpiRuntimeContext(
                directPathCapabilities =
                    listOf(
                        capability("api.example.com", "1111", QuicMode.SOFT_DISABLE),
                        capability("api.example.com", "2222", QuicMode.HARD_DISABLE),
                    ),
            )

        val normalized = normalizeRuntimeContext(ctx)

        val caps = normalized?.directPathCapabilities.orEmpty().filter { it.authority == "api.example.com" }
        assertEquals("both tuples escalated to HARD_DISABLE", 2, caps.size)
        assertEquals(0, caps.count { it.quicMode == QuicMode.SOFT_DISABLE })
        assertEquals(2, caps.count { it.quicMode == QuicMode.HARD_DISABLE })
    }

    // 3. Other hosts are unaffected

    @Test
    fun `hard disable on one host does not affect other hosts`() {
        val ctx =
            RipDpiRuntimeContext(
                directPathCapabilities =
                    listOf(
                        capability("blocked.com", "aaaa", QuicMode.HARD_DISABLE),
                        capability("clean.com", "bbbb", QuicMode.ALLOW),
                        capability("soft.com", "cccc", QuicMode.SOFT_DISABLE),
                    ),
            )

        val normalized = normalizeRuntimeContext(ctx)

        val caps = normalized?.directPathCapabilities.orEmpty()
        assertEquals(QuicMode.HARD_DISABLE, caps.first { it.authority == "blocked.com" }.quicMode)
        assertEquals(QuicMode.ALLOW, caps.first { it.authority == "clean.com" }.quicMode)
        assertEquals(QuicMode.SOFT_DISABLE, caps.first { it.authority == "soft.com" }.quicMode)
    }

    // 4. UDP drops for entire host when HARD_DISABLE

    @Test
    fun `all tuples escalated to hard disable when host has any hard disable tuple`() {
        val ctx =
            RipDpiRuntimeContext(
                directPathCapabilities =
                    listOf(
                        capability("video.example.com", "aaaa", QuicMode.HARD_DISABLE),
                        capability("video.example.com", "bbbb", QuicMode.SOFT_DISABLE),
                    ),
            )

        val normalized = normalizeRuntimeContext(ctx)

        val caps = normalized?.directPathCapabilities.orEmpty().filter { it.authority == "video.example.com" }
        assertEquals(2, caps.size)
        caps.forEach { cap ->
            assertEquals("UDP must be dropped for entire host", QuicMode.HARD_DISABLE, cap.quicMode)
        }
    }

    // 5. TCP/443 (non-UDP) traffic remains allowed — preferred stack unchanged

    @Test
    fun `preferred stack is preserved when hard disable escalation is applied`() {
        val ctx =
            RipDpiRuntimeContext(
                directPathCapabilities =
                    listOf(
                        capability(
                            authority = "example.com",
                            ipSetDigest = "aabb",
                            quicMode = QuicMode.HARD_DISABLE,
                            preferredStack = PreferredStack.H2,
                        ),
                    ),
            )

        val normalized = normalizeRuntimeContext(ctx)

        val cap = normalized?.directPathCapabilities?.first { it.authority == "example.com" }
        assertEquals("TCP stack (H2) must remain", PreferredStack.H2, cap?.preferredStack)
        assertEquals(QuicMode.HARD_DISABLE, cap?.quicMode)
    }

    // helpers

    private fun capability(
        authority: String,
        ipSetDigest: String,
        quicMode: QuicMode,
        preferredStack: PreferredStack = PreferredStack.H3,
    ): RipDpiDirectPathCapability =
        RipDpiDirectPathCapability(
            authority = authority,
            ipSetDigest = ipSetDigest,
            quicMode = quicMode,
            preferredStack = preferredStack,
            outcome = DirectModeOutcome.TRANSPARENT_OK,
        )
}
