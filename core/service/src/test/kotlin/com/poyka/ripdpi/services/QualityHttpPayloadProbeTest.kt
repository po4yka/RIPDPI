package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProfileQualityReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class QualityHttpPayloadProbeTest {
    @Test
    fun `verified body goodput and fully overlapping loaded transaction come from real TLS sockets`() =
        runBlocking {
            QualitySocketFixture(bodyDelayMillis = 400).use { fixture ->
                val result = requireNotNull(fixture.probe().measure(fixture.endpoint, fixture.control, 1))
                assertTrue(result.complete)
                assertEquals(1, result.probeIndex)
                assertEquals(fixture.body.size.toLong(), result.verifiedBytes)
                assertTrue(result.bodyDurationNanos >= 300_000_000)
                assertTrue(result.goodputBytesPerSecond in 1..150_000)
                assertNotNull(result.baselineLatencyMillis)
                assertNotNull(result.loadedLatencyMillis)
                assertEquals(listOf("HEAD", "GET", "HEAD"), fixture.methods)
                assertTrue(fixture.hosts.all { it == "control.example.test" })
            }
        }

    @Test
    fun `fast completed GET preserves goodput without inventing loaded latency`() =
        runBlocking {
            QualitySocketFixture(bodyDelayMillis = 0, loadedHeadDelayMillis = 350).use { fixture ->
                val result = requireNotNull(fixture.probe().measure(fixture.endpoint, fixture.control, 0))
                assertFalse(result.complete)
                assertFalse(result.loadedOverlap)
                assertNull(result.loadedLatencyMillis)
                assertEquals(ProfileQualityReason.LoadNotOverlapped, result.reason)
                assertTrue(result.goodputBytesPerSecond > 0)
            }
        }

    @Test
    fun `headers before delayed first byte cannot launch a loaded control`() =
        runBlocking {
            val delayed =
                QualitySocketFixture(bodyDelayMillis = 0, firstByteDelayMillis = 400, loadedHeadDelayMillis = 100)
            delayed.use { fixture ->
                val result = requireNotNull(fixture.probe().measure(fixture.endpoint, fixture.control, 0))
                assertEquals(ProfileQualityReason.LoadNotOverlapped, result.reason)
                assertTrue(result.bodyDurationNanos < 300_000_000)
                assertFalse(fixture.loadedHeadBeforeBody)
            }
        }

    @Test
    fun `failed HEAD does not erase independently verified GET health`() =
        runBlocking {
            QualitySocketFixture(bodyDelayMillis = 200, headStatus = 503).use { fixture ->
                val result = requireNotNull(fixture.probe().measure(fixture.endpoint, fixture.control, 0))
                assertEquals(ProfileQualityReason.BaselineUnavailable, result.reason)
                assertNull(result.baselineLatencyMillis)
                assertEquals(fixture.body.size.toLong(), result.verifiedBytes)
            }
        }

    @Test
    fun `wrong digest and incomplete body cannot yield goodput`() =
        runBlocking {
            for (truncate in listOf(false, true)) {
                QualitySocketFixture(bodyDelayMillis = 100, truncate = truncate).use { fixture ->
                    val control = if (truncate) fixture.control else fixture.control.copy(sha256 = "0".repeat(64))
                    assertNull(fixture.probe().measure(fixture.endpoint, control, 0))
                }
            }
        }

    @Test
    fun `cancellation releases both actual concurrent HTTP calls`() =
        runBlocking {
            QualitySocketFixture(bodyDelayMillis = 0, stall = true, loadedHeadDelayMillis = 3_000).use { fixture ->
                val pending = async { fixture.probe().measure(fixture.endpoint, fixture.control, 0) }
                yield()
                assertTrue(withContext(Dispatchers.IO) { fixture.bodyStarted.await(3, TimeUnit.SECONDS) })
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
                assertTrue(withContext(Dispatchers.IO) { fixture.bodyClosed.await(3, TimeUnit.SECONDS) })
            }
        }
}

private class QualitySocketFixture(
    private val bodyDelayMillis: Long,
    private val firstByteDelayMillis: Long = 0,
    private val loadedHeadDelayMillis: Long = 10,
    private val headStatus: Int = 200,
    private val truncate: Boolean = false,
    private val stall: Boolean = false,
) : AutoCloseable {
    val body = ByteArray(32_768) { (it % 251).toByte() }
    val control =
        PayloadProbeControl(
            "https://control.example.test/payload",
            body.size,
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
        )
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("control.example.test").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val workers = Executors.newFixedThreadPool(4)
    private val heads = AtomicInteger()
    val methods = CopyOnWriteArrayList<String>()
    val hosts = CopyOnWriteArrayList<String>()

    @Volatile var loadedHeadBeforeBody = false
    val bodyStarted = CountDownLatch(1)
    val bodyClosed = CountDownLatch(1)
    val endpoint = RelayProbeEndpoint("127.0.0.1", listener.localPort)
    private val acceptor =
        thread(isDaemon = true, name = "quality-socks-accept") {
            try {
                repeat(3) {
                    val socket = listener.accept()
                    sockets.add(socket)
                    workers.submit { serve(socket) }
                }
            } catch (_: java.io.IOException) {
                // Listener closes during test teardown.
            }
        }

    fun probe() =
        QualityHttpPayloadProbe(4_000, {
            OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        })

    private fun serve(socket: Socket) {
        var get = false
        try {
            socket.use { accepted ->
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
                    tls.useClientMode = false
                    tls.startHandshake()
                    val reader = tls.inputStream.bufferedReader()
                    val method = reader.readLine().substringBefore(' ')
                    methods.add(method)
                    while (!reader.readLine().isNullOrEmpty()) { /* Bounded test request headers. */ }
                    get = method == "GET"
                    if (!get && heads.incrementAndGet() > 1) {
                        loadedHeadBeforeBody = bodyStarted.count != 0L
                        Thread.sleep(loadedHeadDelayMillis)
                    }
                    val status = if (get) 200 else headStatus
                    tls.outputStream.write(
                        "HTTP/1.1 $status Result\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    tls.outputStream.flush()
                    if (get) sendBody(tls)
                }
            }
        } catch (_: java.io.IOException) {
            // Cancellation and truncated responses are expected test outcomes.
        } catch (
            _: InterruptedException,
        ) {
            Thread.currentThread().interrupt()
        } finally {
            if (get) bodyClosed.countDown()
        }
    }

    private fun sendBody(tls: SSLSocket) {
        Thread.sleep(firstByteDelayMillis)
        tls.outputStream.write(body, 0, 1)
        tls.outputStream.flush()
        bodyStarted.countDown()
        if (stall) {
            while (tls.inputStream.read() != -1) { /* Wait for client cancellation. */ }
        } else {
            Thread.sleep(bodyDelayMillis)
            tls.outputStream.write(body, 1, if (truncate) body.size / 2 else body.size - 1)
            tls.outputStream.flush()
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
        workers.awaitTermination(5, TimeUnit.SECONDS)
        acceptor.join(5_000)
    }
}
