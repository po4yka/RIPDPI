package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract for [NativeBridgeErrorPayload]. The Rust side
 * appends a JSON trailer to the existing Java exception message after
 * a sentinel line; this test asserts that:
 *
 * - **Old / non-typed messages return `null`** so callers fall back to
 *   their existing [NativeError] handling (the "no behavior change for
 *   callers that ignore typed errors" invariant).
 * - **Typed payloads decode**, with all known fields populated.
 * - **Unknown future fields are ignored**, so a newer native side may
 *   add fields without breaking the parser.
 * - **`humanPrefix` recovers the legacy-shaped message** for log lines
 *   that want to render the same text as before.
 */
class NativeBridgeErrorPayloadTest {
    @Test
    fun `parse returns null for a legacy exception message without the sentinel`() {
        // Exactly what the unconverted entry points (poll_telemetry,
        // update_network_snapshot) and pre-feature builds still produce.
        val legacy = "Invalid proxy handle"
        assertNull(NativeBridgeErrorPayload.parse(legacy))
    }

    @Test
    fun `parse returns null for empty or null source`() {
        assertNull(NativeBridgeErrorPayload.parse(null))
        assertNull(NativeBridgeErrorPayload.parse(""))
    }

    @Test
    fun `parse returns null when the sentinel is present but the trailer is empty`() {
        val empty = "Invalid proxy handle\n${NativeBridgeErrorPayload.SENTINEL}\n"
        assertNull(NativeBridgeErrorPayload.parse(empty))
    }

    @Test
    fun `parse returns null when the trailer is not valid JSON`() {
        val garbled = "Invalid proxy handle\n${NativeBridgeErrorPayload.SENTINEL}\n{not json"
        assertNull(NativeBridgeErrorPayload.parse(garbled))
    }

    @Test
    fun `parse decodes a complete typed payload`() {
        val source =
            buildString {
                appendLine("Invalid proxy handle")
                appendLine(NativeBridgeErrorPayload.SENTINEL)
                append(
                    """
                    {
                        "schemaVersion": 1,
                        "domain": "proxy",
                        "code": "start_handle_invalid",
                        "message": "Invalid proxy handle",
                        "causeClass": "java.lang.IllegalArgumentException",
                        "handleState": "invalid_or_unknown",
                        "retryable": false
                    }
                    """.trimIndent(),
                )
            }
        val payload = NativeBridgeErrorPayload.parse(source)
        assertNotNull(payload)
        requireNotNull(payload)
        assertEquals(1, payload.schemaVersion)
        assertEquals("proxy", payload.domain)
        assertEquals("start_handle_invalid", payload.code)
        assertEquals("Invalid proxy handle", payload.message)
        assertEquals("java.lang.IllegalArgumentException", payload.causeClass)
        assertEquals("invalid_or_unknown", payload.handleState)
        assertFalse(payload.retryable)
    }

    @Test
    fun `parse omits optional fields when absent in the JSON`() {
        // Required-only payload — same as `to_json` on the Rust side
        // when no optional builder was called.
        val source =
            buildString {
                appendLine("listener bind failed")
                appendLine(NativeBridgeErrorPayload.SENTINEL)
                append(
                    """{"schemaVersion":1,"domain":"proxy",""" +
                        """"code":"start_listener_open_failed",""" +
                        """"message":"x","retryable":true}""",
                )
            }
        val payload = NativeBridgeErrorPayload.parse(source)
        assertNotNull(payload)
        requireNotNull(payload)
        assertNull(payload.causeClass)
        assertNull(payload.handleState)
        assertTrue(payload.retryable)
    }

    @Test
    fun `parse tolerates unknown future fields produced by a newer native side`() {
        // A future native build may add fields. The parser must ignore
        // them rather than reject the payload.
        val source =
            buildString {
                appendLine("Invalid proxy handle")
                appendLine(NativeBridgeErrorPayload.SENTINEL)
                append(
                    """
                    {
                        "schemaVersion": 1,
                        "domain": "proxy",
                        "code": "start_handle_invalid",
                        "message": "Invalid proxy handle",
                        "retryable": false,
                        "unstableExtension": "future-only",
                        "trace": {"span": "abc-123"}
                    }
                    """.trimIndent(),
                )
            }
        val payload = NativeBridgeErrorPayload.parse(source)
        assertNotNull(payload)
        requireNotNull(payload)
        assertEquals("start_handle_invalid", payload.code)
        assertEquals("proxy", payload.domain)
    }

    @Test
    fun `parse recovers a panic-boundary payload`() {
        // Panic at the proxy boundary surfaces as a RuntimeException
        // whose message is decorated with a typed trailer carrying the
        // `*_panic` code. Mirrors `throw_panic_with_payload`.
        val source =
            buildString {
                appendLine("Proxy session creation panicked: boom")
                appendLine(NativeBridgeErrorPayload.SENTINEL)
                append("""{"schemaVersion":1,"domain":"proxy",""")
                append(""""code":"create_panic","message":"boom","retryable":false}""")
            }
        val payload = NativeBridgeErrorPayload.parse(source)
        assertNotNull(payload)
        requireNotNull(payload)
        assertEquals("create_panic", payload.code)
        assertEquals("proxy", payload.domain)
    }

    @Test
    fun `humanPrefix returns the source unchanged when no sentinel is present`() {
        // Legacy messages pass through verbatim.
        val legacy = "Invalid proxy handle"
        assertEquals(legacy, NativeBridgeErrorPayload.humanPrefix(legacy))
    }

    @Test
    fun `humanPrefix returns the leading line of a decorated message`() {
        val decorated = "Invalid proxy handle\n${NativeBridgeErrorPayload.SENTINEL}\n{\"schemaVersion\":1}"
        assertEquals("Invalid proxy handle", NativeBridgeErrorPayload.humanPrefix(decorated))
    }

    @Test
    fun `humanPrefix returns null for a null source`() {
        assertNull(NativeBridgeErrorPayload.humanPrefix(null))
    }
}
