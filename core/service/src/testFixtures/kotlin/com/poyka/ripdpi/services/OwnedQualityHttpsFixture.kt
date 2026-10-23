package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProfileQualityPolicy
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Owned TLS origin with a test-only trust anchor. Production certificate validation stays enabled. */
class OwnedQualityHttpsFixture : AutoCloseable {
    private val body = ByteArray(262_144) { (it % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private val certificate =
        HeldCertificate
            .Builder()
            .addSubjectAlternativeName("control-a.example.test")
            .addSubjectAlternativeName("control-b.example.test")
            .build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val methods = CopyOnWriteArrayList<String>()
    val port: Int get() = server.localPort

    @Volatile var bodyDelayMillis = 400L

    @Volatile var loadedHeadDelayMillis = 0L
    private var heads =
        java.util.concurrent.atomic
            .AtomicInteger()
    private val acceptor =
        thread(isDaemon = true, name = "owned-quality-origin") {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    sockets.add(socket)
                    workers.submit { serve(socket) }
                }
            } catch (_: java.io.IOException) {
                // Owned listener retirement.
            }
        }

    fun policy() =
        PayloadFailoverPolicy(
            2,
            "select",
            listOf(
                PayloadProbeControl("https://control-a.example.test:$port/payload", body.size, digest),
                PayloadProbeControl("https://control-b.example.test:$port/payload", body.size, digest),
            ),
            60,
            15,
            2,
            2,
            120,
            900,
            ProfileQualityPolicy(2_097_152, 16_777_216, 3_600, 20, 50),
        )

    fun httpProbe(): CandidateHttpPayloadProbe =
        CandidateHttpPayloadProbe(
            OkHttpRelayTcpProbe(),
            System::nanoTime,
            qualityProbe = { timeout ->
                QualityHttpPayloadProbe(timeout, {
                    OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                })
            },
        )

    private fun serve(socket: Socket) {
        try {
            socket.use { accepted ->
                accepted.soTimeout = 15_000
                (serverTls.sslSocketFactory().createSocket(accepted, "control-a.example.test", port, true) as SSLSocket)
                    .use { tls ->
                        tls.useClientMode = false
                        tls.startHandshake()
                        val input = tls.inputStream.bufferedReader()
                        val method = input.readLine().substringBefore(' ')
                        methods.add(method)
                        while (!input.readLine().isNullOrEmpty()) {
                            // Only fixture clients connect.
                        }
                        if (method == "HEAD" && heads.incrementAndGet() % 2 == 0) Thread.sleep(loadedHeadDelayMillis)
                        val output = tls.outputStream
                        output.write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            ).toByteArray(),
                        )
                        output.flush()
                        if (method == "GET") {
                            output.write(body, 0, 1)
                            output.flush()
                            Thread.sleep(bodyDelayMillis)
                            output.write(body, 1, body.size - 1)
                            output.flush()
                        }
                    }
            }
        } catch (_: java.io.IOException) {
            // Cancellation closes the test connection.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS))
        acceptor.join(5_000)
    }
}
