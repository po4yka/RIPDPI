package com.poyka.ripdpi.core.detection.probe

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyProberTest {
    @Test
    fun `HTTP proxy status can arrive one byte at a time`() {
        assertEquals(
            ProxyProber.PortProbeResult.HTTP_CONNECT_PROXY,
            probeResponse("HTTP/1.1 200 Connection established\r\n", fragmentDelayMs = 2L),
        )
    }

    @Test
    fun `incomplete rejected and oversized status lines do not identify a proxy`() {
        listOf(
            "HTTP/1.1 200 Connection established",
            "HTTP/1.1 407 Proxy Authentication Required\r\n",
            "HTTP/1.1 2000 Invalid\r\n",
            "HTTP/1.1 200 ${"x".repeat(256)}\r\n",
            "",
        ).forEach { response ->
            assertEquals(ProxyProber.PortProbeResult.UNKNOWN_TCP_SERVICE, probeResponse(response))
        }
    }

    @Test
    fun `trickled status cannot extend the configured read deadline`() {
        assertEquals(
            ProxyProber.PortProbeResult.UNKNOWN_TCP_SERVICE,
            probeResponse("HTTP/1.1 200 OK\r\n", fragmentDelayMs = 20L, readTimeoutMs = 60),
        )
    }

    private fun probeResponse(
        response: String,
        fragmentDelayMs: Long = 0L,
        readTimeoutMs: Int = 1_000,
    ): ProxyProber.PortProbeResult {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 2_000
                val peer =
                    executor.submit {
                        server.accept().use { socket ->
                            socket.soTimeout = 2_000
                            check(socket.getInputStream().readNBytes(3).size == 3)
                            socket.getOutputStream().write(byteArrayOf(0, 0))
                        }
                        server.accept().use { socket ->
                            socket.soTimeout = 2_000
                            val request = StringBuilder()
                            while (!request.endsWith("\r\n\r\n")) {
                                val byte = socket.getInputStream().read()
                                check(byte >= 0 && request.length < 512)
                                request.append(byte.toChar())
                            }
                            try {
                                response.toByteArray().forEach { byte ->
                                    socket.getOutputStream().write(byte.toInt())
                                    socket.getOutputStream().flush()
                                    if (fragmentDelayMs > 0L) Thread.sleep(fragmentDelayMs)
                                }
                            } catch (_: IOException) {
                                // The probe can reject the status before the peer finishes writing.
                            }
                        }
                    }
                val result = ProxyProber.probePort("127.0.0.1", server.localPort, 1_000, readTimeoutMs)
                peer.get(3, TimeUnit.SECONDS)
                return result
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
