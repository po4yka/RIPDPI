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
                httpMethodSpace = false,
                httpUnixEol = false,
                httpHostPad = false,
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
                httpMethodSpace = true,
                httpUnixEol = true,
                httpHostPad = true,
            )
        assertEquals(7, result.size)
        assertTrue(result.contains(HttpParserEvasionHostMixedCase))
        assertTrue(result.contains(HttpParserEvasionDomainMixedCase))
        assertTrue(result.contains(HttpParserEvasionHostRemoveSpaces))
        assertTrue(result.contains(HttpParserEvasionMethodEol))
        assertTrue(result.contains(HttpParserEvasionMethodSpace))
        assertTrue(result.contains(HttpParserEvasionUnixEol))
        assertTrue(result.contains(HttpParserEvasionHostPad))
    }

    @Test
    fun `single flag returns single evasion`() {
        val result =
            activeHttpParserEvasions(
                hostMixedCase = false,
                domainMixedCase = false,
                hostRemoveSpaces = true,
                httpMethodEol = false,
                httpMethodSpace = false,
                httpUnixEol = false,
                httpHostPad = false,
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
                httpMethodSpace = false,
                httpUnixEol = true,
                httpHostPad = false,
            )
        assertEquals(listOf(HttpParserEvasionUnixEol, HttpParserEvasionMethodEol), result)
    }

    @Test
    fun `host pad and method space append in stable order`() {
        val result =
            activeHttpParserEvasions(
                hostMixedCase = false,
                domainMixedCase = false,
                hostRemoveSpaces = false,
                httpMethodEol = false,
                httpMethodSpace = true,
                httpUnixEol = false,
                httpHostPad = true,
            )

        assertEquals(listOf(HttpParserEvasionMethodSpace, HttpParserEvasionHostPad), result)
    }
}
