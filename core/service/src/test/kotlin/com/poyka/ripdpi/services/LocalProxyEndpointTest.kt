package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyEndpointTest {
    private val path = "/data/user/0/app/files/px-0123"

    private fun snapshot(listenerAddress: String?) =
        NativeRuntimeSnapshot(
            source = "proxy",
            state = "running",
            health = "healthy",
            listenerAddress = listenerAddress,
        )

    @Test
    fun `unix session builds the endpoint from its own path`() {
        val endpoint = resolveLocalProxyEndpoint(snapshot("unix"), authToken = "token", unixPath = path)

        assertEquals(path, endpoint.unixPath)
        assertEquals(VpnLocalProxyUsername, endpoint.username)
        assertEquals("token", endpoint.password)
    }

    @Test
    fun `unix session rejects any other listener address or a missing token`() {
        listOf("127.0.0.1:1080", null, "").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                resolveLocalProxyEndpoint(snapshot(address), authToken = "token", unixPath = path)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveLocalProxyEndpoint(snapshot("unix"), authToken = null, unixPath = path)
        }
    }

    @Test
    fun `tcp session still parses the reported address`() {
        val endpoint = resolveLocalProxyEndpoint(snapshot("127.0.0.1:1080"), authToken = "token")

        assertEquals("127.0.0.1", endpoint.host)
        assertEquals(1080, endpoint.port)
        assertEquals(null, endpoint.unixPath)
    }

    @Test
    fun `endpoint accepts exactly one listener form`() {
        listOf<() -> LocalProxyEndpoint>(
            { LocalProxyEndpoint("127.0.0.1", 1080, unixPath = path) },
            { LocalProxyEndpoint("", 0) },
            { LocalProxyEndpoint("127.0.0.1", 0) },
            { LocalProxyEndpoint("", 0, unixPath = path) },
            { LocalProxyEndpoint("", 0, "ripdpi", "token", unixPath = "px-0123") },
            { LocalProxyEndpoint("", 0, "ripdpi", "token", unixPath = "/" + "a".repeat(107)) },
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `toString omits the path and the credentials`() {
        val text = LocalProxyEndpoint("", 0, "ripdpi", "token", unixPath = path).toString()

        assertFalse(text.contains("px-0123"))
        assertFalse(text.contains("token"))
        assertTrue(text.contains("REDACTED"))
    }
}
