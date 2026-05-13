package com.poyka.ripdpi.diagnostics.dpich

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DiagnosticShareLinkCodecTest {
    @Test
    fun `roundtrip preserves all fields`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x7a,
                timestampMinutes = 123_456,
                asn = 12_389,
                items =
                    listOf(
                        ShareLinkItem(AliveState.YES, DpiState.DETECTED),
                        ShareLinkItem(AliveState.NO, DpiState.NOT_DETECTED),
                        ShareLinkItem(AliveState.UNKNOWN, DpiState.PROBABLY),
                    ),
            )

        val decoded = DiagnosticShareLinkCodec.decode(DiagnosticShareLinkCodec.encode(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun `bit packing two items uses ten lsb-first bits`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x11,
                timestampMinutes = 0,
                asn = 0,
                items =
                    listOf(
                        ShareLinkItem(AliveState.YES, DpiState.DETECTED),
                        ShareLinkItem(AliveState.UNKNOWN, DpiState.UNLIKELY),
                    ),
            )

        val rawPayload = DiagnosticShareLinkCodec.encodePayload(payload, privacyMode = false)

        assertEquals(10, rawPayload.size)
        assertArrayEquals(byteArrayOf(0x45, 0x02), rawPayload.copyOfRange(8, 10))
    }

    @Test
    fun `privacy mode zeroes asn`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x7a,
                timestampMinutes = 123,
                asn = 12_389,
                items = listOf(ShareLinkItem(AliveState.YES, DpiState.POSSIBLE)),
            )

        val decoded = DiagnosticShareLinkCodec.decode(DiagnosticShareLinkCodec.encode(payload, privacyMode = true))

        assertEquals(0, decoded.asn)
    }

    @Test
    fun `decode malformed fragment throws`() {
        assertThrowsShareLinkDecodeError {
            DiagnosticShareLinkCodec.decode("not valid base64url!")
        }
    }

    @Test
    fun `decode truncated payload throws`() {
        val truncated = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0x7a, 0x01, 0x02))

        assertThrowsShareLinkDecodeError {
            DiagnosticShareLinkCodec.decode(truncated)
        }
    }

    @Test
    fun `decode rejects oversized fragment before decoding`() {
        assertThrowsShareLinkDecodeError {
            DiagnosticShareLinkCodec.decode("A".repeat(DiagnosticShareLinkMaxFragmentChars + 1))
        }
    }

    @Test
    fun `decode rejects trailing binary payload data`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x7a,
                timestampMinutes = 1,
                asn = 1,
                items = listOf(ShareLinkItem(AliveState.YES, DpiState.DETECTED)),
            )
        val encodedBytes = Base64.getUrlDecoder().decode(DiagnosticShareLinkCodec.encode(payload))
        val withTrailingData = encodedBytes + byteArrayOf(0x01)
        val fragment = Base64.getUrlEncoder().withoutPadding().encodeToString(withTrailingData)

        assertThrowsShareLinkDecodeError {
            DiagnosticShareLinkCodec.decode(fragment)
        }
    }

    @Test
    fun `commit hash byte is not xor obfuscated`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x5c,
                timestampMinutes = 1,
                asn = 1,
                items = listOf(ShareLinkItem(AliveState.UNKNOWN, DpiState.UNLIKELY)),
            )

        val fragment = DiagnosticShareLinkCodec.encode(payload)
        val encodedBytes = Base64.getUrlDecoder().decode(fragment)

        assertEquals(0x5c, encodedBytes.first().toInt() and 0xff)
    }

    @Test
    fun `large item list encodes within url length budget`() {
        val payload =
            ShareLinkPayload(
                commitHash = 0x42,
                timestampMinutes = 1_048_576,
                asn = 65_535,
                items =
                    List(50) { index ->
                        ShareLinkItem(
                            alive = AliveState.entries[index % AliveState.entries.size],
                            dpi = DpiState.entries[index % DpiState.entries.size],
                        )
                    },
            )

        val fragment = DiagnosticShareLinkCodec.encode(payload)

        assertTrue("fragment length was ${fragment.length}", fragment.length < 600)
    }

    private fun assertThrowsShareLinkDecodeError(block: () -> Unit) {
        try {
            block()
        } catch (_: ShareLinkDecodeError) {
            return
        }
        throw AssertionError("Expected ShareLinkDecodeError")
    }
}
