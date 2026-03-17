package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FailureReasonTest {
    // -- displayMessage ---------------------------------------------------

    @Test
    fun `displayMessage returns message for NativeError`() {
        val reason = FailureReason.NativeError("proxy crashed")
        assertEquals("proxy crashed", reason.displayMessage)
    }

    @Test
    fun `displayMessage returns fixed text for TunnelEstablishmentFailed`() {
        assertEquals("Tunnel establishment failed", FailureReason.TunnelEstablishmentFailed.displayMessage)
    }

    @Test
    fun `displayMessage returns cause message for Unexpected`() {
        val reason = FailureReason.Unexpected(RuntimeException("boom"))
        assertEquals("boom", reason.displayMessage)
    }

    @Test
    fun `displayMessage returns fallback when Unexpected cause has null message`() {
        val reason = FailureReason.Unexpected(RuntimeException())
        assertEquals("Unexpected error", reason.displayMessage)
    }

    // -- classifyFailureReason --------------------------------------------

    @Test
    fun `classifyFailureReason maps NativeError AlreadyRunning to FailureReason NativeError`() {
        val result = classifyFailureReason(NativeError.AlreadyRunning("proxy"))
        assertTrue(result is FailureReason.NativeError)
        assertEquals("proxy is already running", (result as FailureReason.NativeError).message)
    }

    @Test
    fun `classifyFailureReason maps NativeError NativeIoError to FailureReason NativeError`() {
        val result = classifyFailureReason(NativeError.NativeIoError("read failed", IOException("fd closed")))
        assertTrue(result is FailureReason.NativeError)
        assertEquals("read failed", (result as FailureReason.NativeError).message)
    }

    @Test
    fun `classifyFailureReason maps IOException to FailureReason NativeError`() {
        val result = classifyFailureReason(IOException("socket reset"))
        assertTrue(result is FailureReason.NativeError)
        assertEquals("socket reset", (result as FailureReason.NativeError).message)
    }

    @Test
    fun `classifyFailureReason maps IllegalStateException with VPN in tunnel context to TunnelEstablishmentFailed`() {
        val result = classifyFailureReason(IllegalStateException("VPN field not null"), isTunnelContext = true)
        assertTrue(result is FailureReason.TunnelEstablishmentFailed)
    }

    @Test
    fun `classifyFailureReason maps IllegalStateException with VPN in proxy context to NativeError`() {
        val result = classifyFailureReason(IllegalStateException("VPN field not null"), isTunnelContext = false)
        assertTrue(result is FailureReason.NativeError)
    }

    @Test
    fun `classifyFailureReason maps plain IllegalStateException to NativeError`() {
        val result = classifyFailureReason(IllegalStateException("bad state"))
        assertTrue(result is FailureReason.NativeError)
        assertEquals("bad state", (result as FailureReason.NativeError).message)
    }

    @Test
    fun `classifyFailureReason maps unknown Exception to Unexpected`() {
        val ex = UnsupportedOperationException("nope")
        val result = classifyFailureReason(ex)
        assertTrue(result is FailureReason.Unexpected)
        assertEquals(ex, (result as FailureReason.Unexpected).cause)
    }
}
