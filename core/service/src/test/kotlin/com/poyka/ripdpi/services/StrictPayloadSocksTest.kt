package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class StrictPayloadSocksTest {
    private val payload = "x".repeat(32_768)

    private fun control(host: String = "control-a.example.test") =
        PayloadProbeControl(
            "https://$host/payload",
            payload.length,
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) },
        )

    private fun complete() = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\n\r\n$payload"

    @Test
    fun `strict complete HTTPS payload traverses configured SOCKS and verifies identity`() =
        runBlocking {
            StrictSocksFixture(listOf(complete())).use { fixture ->
                assertTrue(fixture.probe(control()).succeeded)
                assertEquals(listOf("control-a.example.test"), fixture.hosts)
                assertTrue(fixture.requestHeaders.any { it.equals("Accept-Encoding: identity", ignoreCase = true) })
            }
        }

    @Test
    fun `untrusted control certificate cannot qualify a relay`() =
        runBlocking {
            StrictSocksFixture(listOf(complete())).use { fixture ->
                assertFalse(OkHttpRelayTcpProbe(3_000, control()).probe(fixture.endpoint, control().url).succeeded)
            }
        }

    @Test
    fun `strict HTTPS rejects truncated oversized wrong digest redirects and no content`() =
        runBlocking {
            val responses =
                listOf(
                    "HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\nshort",
                    "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + payload + "x",
                    "HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\n" + "y".repeat(32_768),
                    "HTTP/1.1 302 Found\r\nLocation: https://control-b.example.test/payload\r\nContent-Length: 0\r\n\r\n",
                    "HTTP/1.1 204 No Content\r\n\r\n",
                    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8000\r\n$payload\r\n",
                )
            for (response in responses) {
                StrictSocksFixture(listOf(response)).use { fixture ->
                    assertFalse(fixture.probe(control()).succeeded)
                    assertEquals(1, fixture.hosts.size)
                }
            }
        }

    @Test
    fun `complete chunked payload succeeds only with terminating chunk`() =
        runBlocking {
            val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8000\r\n$payload\r\n0\r\n\r\n"
            StrictSocksFixture(listOf(response)).use { assertTrue(it.probe(control()).succeeded) }
        }

    @Test
    fun `second independent control can prove the same relay when first control fails`() =
        runBlocking {
            StrictSocksFixture(listOf("HTTP/1.1 503 Error\r\nContent-Length: 0\r\n\r\n", complete())).use { fixture ->
                val policy =
                    PayloadFailoverPolicy(
                        1,
                        "select",
                        listOf(control(), control("control-b.example.test")),
                        60,
                        2,
                        2,
                        2,
                        120,
                        900,
                    )
                val probe =
                    CandidateHttpPayloadProbe(OkHttpRelayTcpProbe(), System::nanoTime) { control, timeout ->
                        fixture.strictProbe(control, timeout)
                    }
                assertTrue(probe.probePayload(fixture.endpoint, policy).succeeded)
                assertEquals(listOf("control-a.example.test", "control-b.example.test"), fixture.hosts)
            }
        }

    @Test
    fun `payload timeout and cancellation close stalled HTTPS sockets`() =
        runBlocking {
            val stalled = "HTTP/1.1 200 OK\r\nContent-Length: 32768\r\n\r\nx"
            StrictSocksFixture(listOf(stalled), stall = true).use { fixture ->
                assertFalse(fixture.probe(control(), 500).succeeded)
                assertTrue(fixture.closed.await(2, TimeUnit.SECONDS))
            }
            StrictSocksFixture(listOf(stalled), stall = true).use { fixture ->
                val pending = async { fixture.probe(control(), 5_000) }
                yield()
                assertTrue(fixture.responseSent.await(3, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                assertTrue(fixture.closed.await(2, TimeUnit.SECONDS))
            }
        }
}

/** TLS terminates only after the SOCKS CONNECT, with ordinary certificate and hostname validation. */
private class StrictSocksFixture(
    responses: List<String>,
    private val stall: Boolean = false,
) : AutoCloseable {
    private val certificate =
        HeldCertificate
            .Builder()
            .addSubjectAlternativeName("control-a.example.test")
            .addSubjectAlternativeName("control-b.example.test")
            .build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val endpoint = RelayProbeEndpoint("127.0.0.1", listener.localPort)
    val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
    val requestHeaders = java.util.concurrent.CopyOnWriteArrayList<String>()
    val responseSent = CountDownLatch(1)
    val closed = CountDownLatch(1)

    @Volatile private var socket: Socket? = null
    private val worker =
        thread(isDaemon = true, name = "strict-payload-socks") {
            try {
                for (response in responses) serve(response)
            } catch (_: java.io.IOException) {
                // A rejected, timed out or cancelled response closes the socket.
            } finally {
                closed.countDown()
            }
        }

    private fun serve(response: String) {
        listener.accept().use { accepted ->
            socket = accepted
            accepted.soTimeout = 5_000
            val input = DataInputStream(accepted.getInputStream())
            val output = accepted.getOutputStream()
            check(input.readUnsignedByte() == 5)
            repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
            output.write(byteArrayOf(5, 0))
            output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
            input.readUnsignedByte()
            check(input.readUnsignedByte() == 3)
            val host = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
            hosts.add(host)
            input.readUnsignedShort()
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 1, -69))
            output.flush()
            (serverTls.sslSocketFactory().createSocket(accepted, host, 443, true) as SSLSocket).use { tls ->
                socket = tls
                tls.useClientMode = false
                tls.startHandshake()
                val reader = tls.inputStream.bufferedReader()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    requestHeaders.add(line)
                    line = reader.readLine()
                }
                tls.outputStream.write(response.toByteArray(Charsets.US_ASCII))
                tls.outputStream.flush()
                responseSent.countDown()
                if (stall) while (tls.inputStream.read() != -1) { /* Wait for cancellation. */ }
            }
        }
    }

    fun strictProbe(
        control: PayloadProbeControl,
        timeout: Long,
    ): RelayTcpProbe =
        OkHttpRelayTcpProbe(timeout, control) {
            OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        }

    suspend fun probe(
        control: PayloadProbeControl,
        timeout: Long = 3_000,
    ) = strictProbe(control, timeout).probe(endpoint, control.url)

    override fun close() {
        socket?.close()
        listener.close()
        worker.join(5_000)
    }
}
