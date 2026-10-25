package com.poyka.ripdpi.platform

import com.poyka.ripdpi.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class UserFacingErrorsTest {
    @Test
    fun `timeouts map to the timeout message`() {
        assertEquals(R.string.error_user_timeout, SocketTimeoutException("read timed out").userMessageRes())
    }

    @Test
    fun `dns and route failures map to the network message`() {
        assertEquals(R.string.error_user_no_network, UnknownHostException("example.org").userMessageRes())
        assertEquals(R.string.error_user_no_network, NoRouteToHostException("no route").userMessageRes())
    }

    @Test
    fun `a refused connection is not reported as a network outage`() {
        assertEquals(R.string.error_user_generic, ConnectException("ECONNREFUSED").userMessageRes())
    }

    @Test
    fun `tls and certificate failures map to the tls message`() {
        assertEquals(R.string.error_user_tls, SSLHandshakeException("handshake failed").userMessageRes())
        assertEquals(
            R.string.error_user_tls,
            IOException("wrapped", CertificateException("untrusted chain")).userMessageRes(),
        )
    }

    @Test
    fun `security exceptions map to the permission message`() {
        assertEquals(R.string.error_user_permission, SecurityException("denied").userMessageRes())
    }

    @Test
    fun `classification looks through wrapping causes`() {
        val wrapped = IllegalStateException("probe failed", IOException(SocketTimeoutException()))

        assertEquals(R.string.error_user_timeout, wrapped.userMessageRes())
    }

    @Test
    fun `unclassified failures use the generic message or the caller fallback`() {
        assertEquals(R.string.error_user_generic, IllegalStateException().userMessageRes())
        assertEquals(
            R.string.owned_stack_browser_request_failed,
            IOException("HTTP 502").userMessageRes(fallback = R.string.owned_stack_browser_request_failed),
        )
    }
}
