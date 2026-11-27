package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsProxyCredentials
import com.poyka.ripdpi.diagnostics.contract.engine.EngineInPathRouteWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProxyCredentialsWire
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticsInPathRouteWireTest {
    private val json = Json
    private val credentials = DiagnosticsProxyCredentials("u", "p")
    private val wireCredentials = EngineProxyCredentialsWire("u", "p")

    private fun lease(
        host: String = "",
        port: Int = 0,
        unixPath: String? = null,
    ) = DiagnosticsInPathRouteLease("runtime", 1, 1, host, port, credentials, unixPath)

    @Test
    fun `loopback route encodes exactly as before`() {
        val route = EngineInPathRouteWire("127.0.0.1", 1080, credentials = wireCredentials)

        assertEquals(
            """{"host":"127.0.0.1","port":1080,"credentials":{"username":"u","password":"p"}}""",
            json.encodeToString(EngineInPathRouteWire.serializer(), route),
        )
    }

    @Test
    fun `unix route carries the path and omits the loopback fields`() {
        val route = EngineInPathRouteWire(unixPath = "/data/px-1.sock", credentials = wireCredentials)
        val encoded = json.encodeToString(EngineInPathRouteWire.serializer(), route)

        assertEquals("""{"unixPath":"/data/px-1.sock","credentials":{"username":"u","password":"p"}}""", encoded)
        assertEquals(route, json.decodeFromString(EngineInPathRouteWire.serializer(), encoded))
        assertFalse(route.toString().contains("px-1"))
    }

    @Test
    fun `lease accepts exactly one valid listener form`() {
        assertNull(lease(host = "127.0.0.1", port = 1080).unixPath)
        assertEquals("/data/px-1.sock", lease(unixPath = "/data/px-1.sock").unixPath)
        assertFalse(lease(unixPath = "/data/px-1.sock").toString().contains("px-1"))
        assertEquals(107, lease(unixPath = "/" + "a".repeat(106)).unixPath?.length)
    }

    @Test
    fun `lease rejects both forms neither form and invalid paths`() {
        assertThrows(IllegalArgumentException::class.java) { lease(host = "127.0.0.1", port = 1080, unixPath = "/p") }
        assertThrows(IllegalArgumentException::class.java) { lease() }
        assertThrows(IllegalArgumentException::class.java) { lease(unixPath = "relative/px.sock") }
        assertThrows(IllegalArgumentException::class.java) { lease(unixPath = "/" + "a".repeat(107)) }
        assertThrows(IllegalArgumentException::class.java) { lease(unixPath = "/data/a\u0000b") }
    }
}
