package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parity vectors with the helper's `extra_headers.rs` tests; the helper is the authority. */
class NaiveExtraHeadersTest {
    private fun error(raw: String): NaiveExtraHeadersError? =
        (parseNaiveExtraHeaders(raw) as? NaiveExtraHeadersResult.Invalid)?.error

    @Test
    fun `normalizes CRLF and LF lines and trims values`() {
        val result = parseNaiveExtraHeaders("X-One:  a b \r\nx-two: c\n\r\n") as NaiveExtraHeadersResult.Valid

        assertEquals("X-One: a b\r\nx-two: c", result.normalized)
        assertEquals(listOf("a b", "c"), result.values)
    }

    @Test
    fun `empty input is valid and has no headers`() {
        assertEquals("", (parseNaiveExtraHeaders("") as NaiveExtraHeadersResult.Valid).normalized)
    }

    @Test
    fun `rejects syntax errors, control characters and non-ASCII values`() {
        val invalid =
            listOf(
                "no-colon",
                "bad name: v",
                ": v",
                ":path: v",
                "X-A: v\u0001",
                "X-A: v\u0000",
                "X-A: v\u007f",
                "X-A: café",
            )
        invalid.forEach { assertEquals(it, NaiveExtraHeadersError.InvalidSyntax, error(it)) }
    }

    @Test
    fun `an empty value is accepted like the helper does`() {
        val result = parseNaiveExtraHeaders("X-A:") as NaiveExtraHeadersResult.Valid

        assertEquals("X-A: ", result.normalized)
        assertTrue(result.values.isEmpty())
    }

    @Test
    fun `duplicate names are accepted like the helper does`() {
        assertEquals(
            "x-a: 1\r\nX-A: 2",
            (parseNaiveExtraHeaders("x-a: 1\nX-A: 2") as NaiveExtraHeadersResult.Valid).normalized,
        )
    }

    @Test
    fun `reserved names are rejected case-insensitively`() {
        listOf(
            "host",
            "proxy-authorization",
            "padding",
            "padding-type-request",
            "x-naive-path",
            "connection",
            "transfer-encoding",
            "content-length",
            "te",
            "upgrade",
            "keep-alive",
            "proxy-connection",
        ).forEach { name ->
            assertEquals(name, NaiveExtraHeadersError.ReservedName, error("${name.uppercase()}: v"))
        }
        assertEquals(null, error("X-Padding: v"))
    }

    @Test
    fun `count cap is sixteen headers`() {
        val atCap = (0 until NaiveExtraHeadersMaxCount).joinToString("\r\n") { "x-$it: v" }

        assertEquals(null, error(atCap))
        assertEquals(NaiveExtraHeadersError.TooLarge, error("$atCap\r\nx-over: v"))
    }

    @Test
    fun `size cap applies to the normalized form the helper receives`() {
        val prefix = "x-big: "
        val value = "a".repeat(NaiveExtraHeadersMaxBytes - prefix.length)

        assertEquals(null, error(prefix + value))
        assertEquals(NaiveExtraHeadersError.TooLarge, error(prefix + value + "a"))
        // Normalization drops the extra spaces: the raw input is over the cap, the sent form is not.
        assertEquals(null, error("x-big:" + " ".repeat(20) + value))
    }
}
