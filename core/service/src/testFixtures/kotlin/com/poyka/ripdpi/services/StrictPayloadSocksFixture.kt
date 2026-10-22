package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadProbeControl
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** TLS terminates only after the SOCKS CONNECT, with ordinary certificate and hostname validation. */
class StrictPayloadSocksFixture(
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

    private fun strictProbe(
        control: PayloadProbeControl,
        timeout: Long,
    ): RelayTcpProbe =
        OkHttpRelayTcpProbe(timeout, control) {
            OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        }

    suspend fun probe(
        control: PayloadProbeControl,
        timeout: Long = 3_000,
    ): Boolean = strictProbe(control, timeout).probe(endpoint, control.url).succeeded

    /** Real TLS/body verifier; the test trust anchor never changes production trust. */
    fun httpProbe(): CandidateHttpPayloadProbe =
        CandidateHttpPayloadProbe(OkHttpRelayTcpProbe(), System::nanoTime) { control, timeout ->
            strictProbe(control, timeout)
        }

    override fun close() {
        socket?.close()
        listener.close()
        worker.join(5_000)
    }
}
