package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVlessCapabilitiesTest {
    @Test
    fun `supported combinations agree with the native wire transports`() {
        val supported =
            listOf(
                Selection("reality", "reality_tcp", "", "auto"),
                Selection("reality", "reality_tcp", "xtls-rprx-vision", "auto"),
                Selection("reality", "reality_tcp", "xtls-rprx-vision-udp443", "auto"),
                Selection("reality", "xhttp", "", "auto"),
                Selection("reality", "xhttp", "none", "stream-one"),
                Selection("reality", "xhttp", "off", "stream-up"),
                Selection("tls", "xhttp", "", "stream-up"),
                Selection("tls", "xhttp", "", "stream-one"),
            )
        supported.forEach { selection ->
            assertNull(
                NativeVlessCapabilities.rejection(
                    selection.security,
                    selection.transport,
                    selection.flow,
                    selection.mode,
                ),
            )
        }
        val rejected =
            listOf(
                Selection("tls", "reality_tcp", "", "auto"),
                Selection("tls", "xhttp", "", "auto"),
                Selection("tls", "xhttp", "", ""),
                Selection("reality", "xhttp", "xtls-rprx-vision", "stream-one"),
                Selection("reality", "xhttp", "", "packet-up"),
                Selection("reality", "xhttp", "", "stream-down"),
                Selection("reality", "grpc", "", "auto"),
                Selection("none", "reality_tcp", "", "auto"),
                Selection("reality", "reality_tcp", "unknown-flow", "auto"),
            )
        rejected.forEach { selection ->
            assertNotNull(
                NativeVlessCapabilities.rejection(
                    selection.security,
                    selection.transport,
                    selection.flow,
                    selection.mode,
                ),
            )
        }
    }

    @Test
    fun `UDP requires REALITY TCP Vision or xHTTP and reasons do not include values`() {
        assertNull(NativeVlessCapabilities.rejection("reality", "reality_tcp", RelayVlessFlowVision, udpEnabled = true))
        assertNotNull(NativeVlessCapabilities.rejection("reality", "reality_tcp", "", udpEnabled = true))
        assertNull(NativeVlessCapabilities.rejection("tls", "xhttp", "", "stream-one", udpEnabled = true))
        assertNull(NativeVlessCapabilities.rejection("tls", "xhttp", "none", "stream-up", udpEnabled = true))
        assertNull(NativeVlessCapabilities.rejection("reality", "xhttp", "", "auto", udpEnabled = true))
        assertTrue(NativeVlessCapabilities.supportsUdp("tls", "xhttp", ""))
        assertFalse(NativeVlessCapabilities.supportsUdp("tls", "reality_tcp", RelayVlessFlowVision))
        assertFalse(NativeVlessCapabilities.supportsUdp("reality", "xhttp", RelayVlessFlowVision))
        val input = "sensitive-unrecognized-option"
        assertFalse(NativeVlessCapabilities.rejection("reality", input, "").orEmpty().contains(input))
        assertFalse(NativeVlessCapabilities.rejection("reality", "reality_tcp", input).orEmpty().contains(input))
        assertFalse(NativeVlessCapabilities.rejection("reality", "xhttp", "", input).orEmpty().contains(input))
    }

    @Test
    fun `editor choices exclude combinations the native backend cannot build`() {
        assertEquals(listOf("xhttp"), NativeVlessCapabilities.transports("tls"))
        assertEquals(listOf("stream-up", "stream-one"), NativeVlessCapabilities.xhttpModes("tls"))
        assertEquals(listOf("auto", "stream-up", "stream-one"), NativeVlessCapabilities.xhttpModes("reality"))
        assertEquals(listOf(""), NativeVlessCapabilities.flows("reality", "xhttp"))
        assertEquals("", NativeVlessCapabilities.defaultFlow("reality", "xhttp"))
        assertEquals(RelayVlessFlowVision, NativeVlessCapabilities.defaultFlow("reality", "reality_tcp"))
    }
}

private data class Selection(
    val security: String,
    val transport: String,
    val flow: String,
    val mode: String,
)
