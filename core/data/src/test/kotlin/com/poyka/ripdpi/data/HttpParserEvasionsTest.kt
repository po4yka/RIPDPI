package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpParserEvasionsTest {
    @Test
    fun `all flags false returns empty list`() {
        val result =
            activeHttpParserEvasions(
                hostMixedCase = false,
                domainMixedCase = false,
                hostRemoveSpaces = false,
                httpMethodEol = false,
                httpUnixEol = false,
            )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `all flags true returns all evasions`() {
        val result =
            activeHttpParserEvasions(
                hostMixedCase = true,
                domainMixedCase = true,
                hostRemoveSpaces = true,
                httpMethodEol = true,
                httpUnixEol = true,
            )
        assertEquals(5, result.size)
        assertTrue(result.contains(HttpParserEvasionHostMixedCase))
        assertTrue(result.contains(HttpParserEvasionDomainMixedCase))
        assertTrue(result.contains(HttpParserEvasionHostRemoveSpaces))
        assertTrue(result.contains(HttpParserEvasionMethodEol))
        assertTrue(result.contains(HttpParserEvasionUnixEol))
    }

    @Test
    fun `single flag returns single evasion`() {
        val result =
            activeHttpParserEvasions(
                hostMixedCase = false,
                domainMixedCase = false,
                hostRemoveSpaces = true,
                httpMethodEol = false,
                httpUnixEol = false,
            )
        assertEquals(listOf(HttpParserEvasionHostRemoveSpaces), result)
    }

    @Test
    fun `unix eol appears before method eol in list`() {
        val result =
            activeHttpParserEvasions(
                hostMixedCase = false,
                domainMixedCase = false,
                hostRemoveSpaces = false,
                httpMethodEol = true,
                httpUnixEol = true,
            )
        assertEquals(listOf(HttpParserEvasionUnixEol, HttpParserEvasionMethodEol), result)
    }
}
