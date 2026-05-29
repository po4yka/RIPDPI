package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the native reference suite for
 * `ripdpi_runtime_policy::app_family_memory::AppFamilyMemory` so the Kotlin
 * live owner and the Rust reference stay behaviourally identical, plus the
 * conservative-by-default guarantees that close the epic's NO_TCP_FALLBACK
 * ship-definition item.
 */
class NoTcpFallbackAppMemoryTest {
    @Test
    fun `mark and lookup same version returns true`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("com.example.app", 42)
        assertTrue(mem.shouldSkipSoftDisable("com.example.app", 42))
    }

    @Test
    fun `mark v1 lookup v2 returns false - reverts on app version change`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("com.example.app", 1)
        assertFalse(mem.shouldSkipSoftDisable("com.example.app", 2))
    }

    @Test
    fun `invalidateOnAppUpdate removes entry`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("com.example.app", 10)
        mem.invalidateOnAppUpdate("com.example.app", 11)
        assertFalse(mem.shouldSkipSoftDisable("com.example.app", 10))
        assertFalse(mem.shouldSkipSoftDisable("com.example.app", 11))
    }

    @Test
    fun `invalidateOnAppUpdate is a no-op when version matches`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("com.example.app", 10)
        mem.invalidateOnAppUpdate("com.example.app", 10)
        assertTrue(mem.shouldSkipSoftDisable("com.example.app", 10))
    }

    @Test
    fun `lookup of unmarked package returns false`() {
        val mem = NoTcpFallbackAppMemory()
        assertFalse(mem.shouldSkipSoftDisable("com.unknown.app", 1))
    }

    @Test
    fun `marking package A does not affect package B - per-app isolation`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("com.example.a", 5)
        assertTrue(mem.shouldSkipSoftDisable("com.example.a", 5))
        assertFalse(mem.shouldSkipSoftDisable("com.example.b", 5))
    }

    @Test
    fun `blank package identity is never recorded - conservative by default`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("   ", 7)
        assertFalse(mem.shouldSkipSoftDisable("   ", 7))
        assertFalse(mem.shouldSkipSoftDisable("", 7))
    }

    @Test
    fun `clear drops every mark`() {
        val mem = NoTcpFallbackAppMemory()
        mem.recordNoTcpFallback("com.example.a", 5)
        mem.recordNoTcpFallback("com.example.b", 9)
        mem.clear()
        assertFalse(mem.shouldSkipSoftDisable("com.example.a", 5))
        assertFalse(mem.shouldSkipSoftDisable("com.example.b", 9))
    }
}
