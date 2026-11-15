package com.poyka.ripdpi.core.detection.probe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyProberTest {
    @Test
    fun `HTTP response head can arrive one byte at a time`() {
        assertEquals(
            ProxyProber.PortProbeResult.HTTP_CONNECT_PROXY,
            probeResponse("HTTP/1.1 200 Connection established\r\nServer: local\r\n\r\n", fragmentDelayMs = 2L),
        )
    }

    @Test
    fun `complete final 2xx succeeds after informational responses`() {
        val head = "HTTP/1.1 103 Early Hints\r\nLink: </local>\r\n\r\nHTTP/1.1 201 Created\r\nServer: local\r\n\r\n"
        assertEquals(ProxyProber.PortProbeResult.HTTP_CONNECT_PROXY, probeResponse(head))
    }

    @Test
    fun `incomplete and malformed HTTP replies preserve partial status`() {
        listOf(
            "HTTP/1.1 200 Connection established",
            "HTTP/1.1 200 Connection established\r\n",
            "HTTP/1.1 200 OK\r\nBad Field: local\r\n\r\n",
            "HTTP/1.1 200 OK\r\n${"x".repeat(4096)}\r\n\r\n",
            "HTTP/1.1 100 Continue\r\n\r\n".repeat(9) + "HTTP/1.1 200 OK\r\n\r\n",
            "HTTP/1.1 2000 Invalid\r\n",
            "HTTP/1.1 200 ${"x".repeat(256)}\r\n",
        ).forEach { response ->
            assertEquals(ProxyProber.PortProbeResult.PARTIAL, probeResponse(response))
        }
        assertEquals(ProxyProber.PortProbeResult.UNKNOWN_TCP_SERVICE, probeResponse(""))
    }

    @Test
    fun `complete HTTP authentication refusal is classified separately`() {
        assertEquals(
            ProxyProber.PortProbeResult.REJECTED,
            probeResponse("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"),
        )
    }

    @Test
    fun `trickled status cannot extend the configured read deadline`() {
        assertEquals(
            ProxyProber.PortProbeResult.TIMED_OUT,
            probeResponse("HTTP/1.1 200 OK\r\n\r\n", fragmentDelayMs = 20L, readTimeoutMs = 60),
        )
    }

    @Test(timeout = 6_000)
    fun `cancellation closes a blocked proxy socket`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 3_000
                val greetingRead = CompletableDeferred<Unit>()
                val peer =
                    async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            socket.soTimeout = 3_000
                            val input = socket.getInputStream()
                            assertEquals(3, input.readNBytes(3).size)
                            greetingRead.complete(Unit)
                            assertEquals(-1, input.read())
                        }
                    }
                val probe =
                    launch(Dispatchers.IO) {
                        ProxyProber.probePort("127.0.0.1", server.localPort, 2_000, 5_000)
                    }
                withTimeout(2_000) { greetingRead.await() }
                withTimeout(2_000) {
                    probe.cancelAndJoin()
                    peer.await()
                }
            }
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
                val result = runBlocking { ProxyProber.probePort("127.0.0.1", server.localPort, 1_000, readTimeoutMs) }
                peer.get(3, TimeUnit.SECONDS)
                return result
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
