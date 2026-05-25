package com.poyka.ripdpi.diagnostics.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplayArchiveRedactorTest {
    private val redactor = ReplayArchiveRedactor()

    @Test
    fun ipv4_isReplaced() {
        assertEquals(
            "resolved <IP_REDACTED>",
            redactor.redact("resolved 1.2.3.4"),
        )
    }

    @Test
    fun ipv4_inMiddleOfSentence_isReplaced() {
        assertEquals(
            "DNS returned <IP_REDACTED> in 12 ms",
            redactor.redact("DNS returned 192.168.1.1 in 12 ms"),
        )
    }

    @Test
    fun multipleIpv4_areAllReplaced() {
        assertEquals(
            "via <IP_REDACTED> then <IP_REDACTED>",
            redactor.redact("via 1.2.3.4 then 10.20.30.40"),
        )
    }

    @Test
    fun ipv6Compressed_isReplaced() {
        assertEquals(
            "resolved <IP_REDACTED>",
            redactor.redact("resolved 2001:db8::1"),
        )
    }

    @Test
    fun ipv6Loopback_isReplaced() {
        assertEquals(
            "host <IP_REDACTED> reachable",
            redactor.redact("host ::1 reachable"),
        )
    }

    @Test
    fun ipv6Full_isReplaced() {
        assertEquals(
            "via <IP_REDACTED>",
            redactor.redact("via 2001:0db8:0000:0000:0000:ff00:0042:8329"),
        )
    }

    @Test
    fun mixedIpv4AndIpv6_areBothReplaced() {
        val result = redactor.redact("dual-stack 10.0.0.1 and fe80::1234")
        assertEquals("dual-stack <IP_REDACTED> and <IP_REDACTED>", result)
    }

    @Test
    fun noIp_passesThroughUnchanged() {
        val original = "TLS 1.3 handshake completed in 47 ms"
        assertEquals(original, redactor.redact(original))
    }

    @Test
    fun timestampWithColons_isNotRedacted() {
        // Time strings like "12:34:56" carry only two colons and no "::"
        // — the IPv6 pattern requires either 7 colons or "::" shorthand,
        // so this must pass through unchanged.
        val original = "started at 12:34:56"
        assertEquals(original, redactor.redact(original))
    }

    @Test
    fun portToken_isNotRedacted() {
        val result = redactor.redact("listening on port:443")
        assertEquals("listening on port:443", result)
        assertFalse("port should not be redacted", result.contains("<IP_REDACTED>"))
    }

    @Test
    fun emptyString_passesThrough() {
        assertEquals("", redactor.redact(""))
    }
}
