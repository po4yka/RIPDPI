package com.poyka.ripdpi.services

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RelayTcpPayloadProbeTest {
    @Test
    fun `complete payload and empty 204 succeed through configured socks path`() =
        runBlocking {
            for (response in listOf(
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello",
                "HTTP/1.1 204 No Content\r\n\r\n",
            )) {
                HttpSocksFixture(response).use { fixture ->
                    assertTrue(fixture.probe().succeeded)
                    assertEquals("GET /configured-probe HTTP/1.1", fixture.requestLine)
                }
            }
        }

    @Test
    fun `headers without complete declared payload do not prove reachability`() =
        runBlocking {
            HttpSocksFixture("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nshort").use {
                assertFalse(it.probe().succeeded)
            }
        }

    @Test
    fun `chunked framing must terminate and 204 cannot claim missing payload`() =
        runBlocking {
            val incomplete =
                listOf(
                    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n",
                    "HTTP/1.1 204 No Content\r\nContent-Length: 8\r\n\r\n",
                )
            for (response in incomplete) {
                HttpSocksFixture(response).use { assertFalse(it.probe().succeeded) }
            }
            HttpSocksFixture("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n").use {
                assertTrue(it.probe().succeeded)
            }
        }

    @Test
    fun `declared and streamed oversized payloads are rejected`() =
        runBlocking {
            for (header in listOf("Content-Length: 65537\r\n", "")) {
                HttpSocksFixture("HTTP/1.1 200 OK\r\n${header}Connection: close\r\n\r\n" + "x".repeat(65_537)).use {
                    assertFalse(it.probe().succeeded)
                }
            }
        }

    @Test
    fun `exact payload limit succeeds only after complete body`() =
        runBlocking {
            HttpSocksFixture("HTTP/1.1 200 OK\r\nContent-Length: 65536\r\n\r\n" + "x".repeat(65_536)).use {
                assertTrue(it.probe().succeeded)
            }
        }

    @Test
    fun `non successful HTTP status is rejected`() =
        runBlocking {
            HttpSocksFixture("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n").use {
                assertEquals(RelayProbeFailure.TcpHttpStatus, it.probe().failure)
            }
        }

    @Test
    fun `stalled body remains bounded by call timeout`() =
        runBlocking {
            HttpSocksFixture("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nx", stall = true).use {
                val started = System.nanoTime()
                assertFalse(it.probe(timeoutMillis = 200).succeeded)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            }
        }

    @Test
    fun `cancellation closes a stalled payload call`() =
        runBlocking {
            HttpSocksFixture("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nx", stall = true).use { fixture ->
                val pending = async { fixture.probe() }
                // The async task must run before blocking on a real fixture latch.
                kotlinx.coroutines.yield()
                assertTrue(fixture.responseSent.await(2, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                assertTrue(fixture.peerClosed.await(2, TimeUnit.SECONDS))
            }
        }
}

private class HttpSocksFixture(
    private val response: String,
    private val stall: Boolean = false,
) : AutoCloseable {
    private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))

    @Volatile private var socket: Socket? = null

    @Volatile var requestLine: String? = null
        private set
    val responseSent = CountDownLatch(1)
    val peerClosed = CountDownLatch(1)
    private val worker =
        thread(isDaemon = true, name = "http-payload-socks-fixture") {
            try {
                listener.accept().use { accepted ->
                    socket = accepted
                    accepted.soTimeout = 3_000
                    val input = DataInputStream(accepted.getInputStream())
                    val output = accepted.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                    output.write(byteArrayOf(5, 0))
                    output.flush()
                    check(input.readUnsignedByte() == 5)
                    check(input.readUnsignedByte() == 1)
                    input.readUnsignedByte()
                    when (input.readUnsignedByte()) {
                        1 -> repeat(4) { input.readUnsignedByte() }
                        3 -> repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
                        4 -> repeat(16) { input.readUnsignedByte() }
                        else -> error("invalid SOCKS address")
                    }
                    input.readUnsignedShort()
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                    output.flush()
                    val reader = accepted.getInputStream().bufferedReader()
                    requestLine = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume headers. */ }
                    output.write(response.toByteArray(Charsets.US_ASCII))
                    output.flush()
                    responseSent.countDown()
                    if (stall) {
                        while (input.read() != -1) { /* Wait until the client closes. */ }
                        peerClosed.countDown()
                    }
                }
            } catch (_: java.io.IOException) {
                // The client rejects large/incomplete bodies or the fixture is closed.
                if (stall) peerClosed.countDown()
            }
        }

    suspend fun probe(timeoutMillis: Long = 1_000): RelayTcpProbeResult =
        OkHttpRelayTcpProbe(timeoutMillis).probe(
            RelayProbeEndpoint("127.0.0.1", listener.localPort),
            "http://payload.example/configured-probe",
        )

    override fun close() {
        socket?.close()
        listener.close()
        worker.join(3_000)
    }
}
