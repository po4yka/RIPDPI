package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ServiceFailureTest {
    // -- displayMessage --

    @Test
    fun `NativeError display message uses provided message`() {
        val reason = FailureReason.NativeError("proxy exited unexpectedly")
        assertEquals("proxy exited unexpectedly", reason.displayMessage)
    }

    @Test
    fun `TunnelEstablishmentFailed display message is descriptive`() {
        assertEquals("Tunnel establishment failed", FailureReason.TunnelEstablishmentFailed.displayMessage)
    }

    @Test
    fun `Unexpected display message uses cause message`() {
        val reason = FailureReason.Unexpected(RuntimeException("something broke"))
        assertEquals("something broke", reason.displayMessage)
    }

    @Test
    fun `Unexpected display message falls back when cause has no message`() {
        val reason = FailureReason.Unexpected(RuntimeException())
        assertEquals("Unexpected error", reason.displayMessage)
    }

    // -- classifyFailureReason --

    @Test
    fun `classifyFailureReason maps NativeError to NativeError reason`() {
        val error = NativeError.AlreadyRunning("proxy")
        val result = classifyFailureReason(error)
        assertTrue(result is FailureReason.NativeError)
    }

    @Test
    fun `classifyFailureReason maps IOException to NativeError reason`() {
        val error = IOException("socket closed")
        val result = classifyFailureReason(error)
        assertTrue(result is FailureReason.NativeError)
        assertEquals("socket closed", (result as FailureReason.NativeError).message)
    }

    @Test
    fun `classifyFailureReason maps VPN IllegalStateException to tunnel failure`() {
        val error = IllegalStateException("VPN field not null")
        val result = classifyFailureReason(error, isTunnelContext = true)
        assertEquals(FailureReason.TunnelEstablishmentFailed, result)
    }

    @Test
    fun `classifyFailureReason maps tunnel IllegalStateException to tunnel failure`() {
        val error = IllegalStateException("tunnel fd failed")
        val result = classifyFailureReason(error, isTunnelContext = true)
        assertEquals(FailureReason.TunnelEstablishmentFailed, result)
    }

    @Test
    fun `classifyFailureReason maps non-tunnel IllegalStateException to NativeError`() {
        val error = IllegalStateException("something else")
        val result = classifyFailureReason(error, isTunnelContext = true)
        assertTrue(result is FailureReason.NativeError)
    }

    @Test
    fun `classifyFailureReason maps non-tunnel context IllegalStateException to NativeError`() {
        val error = IllegalStateException("VPN error")
        val result = classifyFailureReason(error, isTunnelContext = false)
        assertTrue(result is FailureReason.NativeError)
    }

    @Test
    fun `classifyFailureReason maps unknown exception to Unexpected`() {
        val error = UnsupportedOperationException("not supported")
        val result = classifyFailureReason(error)
        assertTrue(result is FailureReason.Unexpected)
        assertEquals("not supported", (result as FailureReason.Unexpected).cause.message)
    }

    @Test
    fun `classifyFailureReason handles IOException with no message`() {
        val error = IOException()
        val result = classifyFailureReason(error)
        assertTrue(result is FailureReason.NativeError)
        assertEquals("I/O error", (result as FailureReason.NativeError).message)
    }
}
