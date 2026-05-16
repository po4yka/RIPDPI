package com.poyka.ripdpi.services.redaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsModeOptInTtlTest {
    @Test
    fun `diagnostics mode is inactive by default`() {
        val mode = DiagnosticsMode(clock = { 1_000L })
        assertFalse(mode.isActive)
    }

    @Test
    fun `bundle export is denied before opt-in`() {
        val mode = DiagnosticsMode(clock = { 1_000L })
        assertFalse(mode.canExportBundle())
    }

    @Test
    fun `diagnostics mode becomes active after explicit enable`() {
        var now = 1_000L
        val mode = DiagnosticsMode(clock = { now })
        mode.enable(ttlMs = 60_000L)
        assertTrue(mode.isActive)
    }

    @Test
    fun `diagnostics mode expires after ttl elapses`() {
        var now = 1_000L
        val mode = DiagnosticsMode(clock = { now })
        mode.enable(ttlMs = 500L)

        assertTrue("should be active immediately after enable", mode.isActive)

        now = 1_501L
        assertFalse("should be inactive after TTL elapses", mode.isActive)
    }

    @Test
    fun `bundle export is allowed while mode is active`() {
        var now = 1_000L
        val mode = DiagnosticsMode(clock = { now })
        mode.enable(ttlMs = 60_000L)
        assertTrue(mode.canExportBundle())
    }

    @Test
    fun `bundle export is denied after mode expires`() {
        var now = 1_000L
        val mode = DiagnosticsMode(clock = { now })
        mode.enable(ttlMs = 500L)

        now = 1_501L
        assertFalse(mode.canExportBundle())
    }

    @Test
    fun `explicit disable deactivates mode before ttl`() {
        var now = 1_000L
        val mode = DiagnosticsMode(clock = { now })
        mode.enable(ttlMs = 60_000L)
        mode.disable()
        assertFalse(mode.isActive)
    }
}
