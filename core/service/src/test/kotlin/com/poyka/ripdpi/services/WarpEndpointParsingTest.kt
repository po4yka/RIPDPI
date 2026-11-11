package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpEndpointParsingTest {
    @Test
    fun `provider numeric socket addresses retain the service port`() {
        val parsed =
            parseWarpEndpoint("engage.cloudflareclient.com:2408", "162.159.192.6:0", "[2606:4700:d0::6]:0", "fixture")

        assertEquals("engage.cloudflareclient.com", parsed.host)
        assertEquals("162.159.192.6", parsed.ipv4)
        assertEquals("2606:4700:d0::6", parsed.ipv6)
        assertEquals(2408, parsed.port)
    }

    @Test
    fun `bare numeric addresses and bracketed authority remain supported`() {
        val parsed = parseWarpEndpoint("[::1]:4500", "127.0.0.1", "::1", "fixture")

        assertEquals("::1", parsed.host)
        assertEquals("127.0.0.1", parsed.ipv4)
        assertEquals("::1", parsed.ipv6)
        assertEquals(4500, parsed.port)
    }

    @Test
    fun `malformed provider endpoints are rejected without including input in errors`() {
        val malformed =
            listOf(
                Triple("example.com:0", null, null),
                Triple("example.com:65536", null, null),
                Triple("example.com:+2408", null, null),
                Triple("example.com..:2408", null, null),
                Triple("999.1.2.3:2408", null, null),
                Triple("[::1:2408", null, null),
                Triple("example.com:2408", "127.0.0.1:65536", null),
                Triple("example.com:2408", "127.0.0.1:private-input", null),
                Triple("example.com:2408", "999.1.2.3:0", null),
                Triple("example.com:2408", null, "[::1]:-1"),
                Triple("example.com:2408", null, "[::1]:0suffix"),
                Triple("example.com:2408", null, "[not-an-ip]:0"),
            )
        malformed.forEach { (host, ipv4, ipv6) ->
            val error = runCatching { parseWarpEndpoint(host, ipv4, ipv6, "fixture") }.exceptionOrNull()
            assertTrue(error is WarpProvisioningException.MalformedResponse)
            assertEquals("WARP provisioning returned an invalid endpoint", error?.message)
        }
    }
}
