package com.poyka.ripdpi.core.detection.dpi

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.reflect.KClass

class DpiErrorClassifierTest {
    @Test
    fun `tls wrong version number classified as tls spoof`() {
        val error = IOException("request failed", SSLException("wrong version number"))

        assertEquals(DpiProbeError.TlsSpoof, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `tls record overflow classified as tls spoof`() {
        val error = SSLException("record overflow")

        assertEquals(DpiProbeError.TlsSpoof, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `unrecognized name alert classified as tls alert sni`() {
        val error = SSLHandshakeException("Received fatal alert: unrecognized_name")

        assertEquals(DpiProbeError.TlsAlertSni, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `handshake failure alert classified as tls alert handshake`() {
        val error = SSLHandshakeException("Received fatal alert: handshake_failure")

        assertEquals(DpiProbeError.TlsAlertHandshake, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `protocol version alert classified as tls block version`() {
        val error = SSLHandshakeException("Received fatal alert: protocol_version")

        assertEquals(DpiProbeError.TlsBlockVersion, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `eof at tls handshake stage classified as tls rst`() {
        val error = EOFException("EOF during handshake")

        assertEquals(DpiProbeError.TlsRst, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `eof while reading data classified as tls eof`() {
        val error = EOFException("unexpected EOF")

        assertEquals(DpiProbeError.TlsEof, DpiErrorClassifier.classify(error, ProbeStage.READING_DATA))
    }

    @Test
    fun `connect timeout at tls stage classified as tls drop`() {
        val error = SocketTimeoutException("connect timed out")

        assertEquals(DpiProbeError.TlsDrop, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `cert expired classified as tls mitm`() {
        val error = SSLPeerUnverifiedException("Certificate expired")

        assertEquals(DpiProbeError.TlsMitm, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `hostname mismatch in handshake exception classified as tls mitm`() {
        val error = SSLHandshakeException("Hostname mismatch: certificate is not valid for host")

        assertEquals(DpiProbeError.TlsMitm, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `connect timeout at tcp stage classified as syn drop`() {
        val error = SocketTimeoutException("connect timed out")

        assertEquals(DpiProbeError.SynDrop, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `connection reset at tcp stage classified as tcp rst`() {
        val error = SocketException("Connection reset")

        assertEquals(DpiProbeError.TcpRst, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `connection reset at tls stage classified as tls rst tls`() {
        val error = SocketException("Connection reset")

        assertEquals(DpiProbeError.TlsRstTls, DpiErrorClassifier.classify(error, ProbeStage.TLS_HANDSHAKE))
    }

    @Test
    fun `connection aborted at tcp stage classified as tcp abort`() {
        val error = SocketException("Connection aborted")

        assertEquals(DpiProbeError.TcpAbort, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `unknown host exception classified as dns fail`() {
        val error = UnknownHostException("example.test")

        assertEquals(DpiProbeError.DnsFail, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `connection refused classified as refused`() {
        val error = ConnectException("Connection refused")

        assertEquals(DpiProbeError.Refused, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `network unreachable classified as net unreachable`() {
        val error = SocketException("ENETUNREACH: Network is unreachable")

        assertEquals(DpiProbeError.NetUnreach, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `host unreachable classified as host unreachable`() {
        val error = NoRouteToHostException("EHOSTUNREACH: No route to host")

        assertEquals(DpiProbeError.HostUnreach, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `cause chain traversal depth 10`() {
        val error =
            (1..9).fold<_, Throwable>(UnknownHostException("blocked.example")) { cause, index ->
                IOException("wrapper $index", cause)
            }

        assertEquals(DpiProbeError.DnsFail, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }

    @Test
    fun `cause beyond traversal depth is not classified`() {
        val error =
            (1..10).fold<_, Throwable>(UnknownHostException("blocked.example")) { cause, index ->
                IOException("wrapper $index", cause)
            }

        assertEquals(DpiProbeError.Unknown, DpiErrorClassifier.classify(error, ProbeStage.TCP_CONNECT))
    }
}

class OkHttpProbeEventListenerTest {
    @Test
    fun `event listener tracks probe stage transitions`() {
        val listener = OkHttpProbeEventListener()
        val call = StubCall()

        listener.connectStart(call, InetSocketAddress("127.0.0.1", 443), Proxy.NO_PROXY)
        assertEquals(ProbeStage.TCP_CONNECT, listener.currentStage)

        listener.secureConnectStart(call)
        assertEquals(ProbeStage.TLS_HANDSHAKE, listener.currentStage)

        listener.requestHeadersStart(call)
        assertEquals(ProbeStage.SENDING_DATA, listener.currentStage)

        listener.responseHeadersStart(call)
        assertEquals(ProbeStage.READING_DATA, listener.currentStage)
    }

    private class StubCall : Call {
        override fun request(): Request = Request.Builder().url("https://example.test/").build()

        override fun execute(): Response = error("not used")

        override fun enqueue(responseCallback: Callback) = error("not used")

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = StubCall()

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(
            type: KClass<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun <T : Any> tag(
            type: Class<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()
    }
}
