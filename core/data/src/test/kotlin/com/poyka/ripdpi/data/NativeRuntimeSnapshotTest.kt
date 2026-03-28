package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRuntimeSnapshotTest {
    @Test
    fun `idle snapshot has default values`() {
        val snapshot = NativeRuntimeSnapshot.idle("proxy")
        assertEquals("proxy", snapshot.source)
        assertEquals("idle", snapshot.state)
        assertEquals("idle", snapshot.health)
        assertEquals(0L, snapshot.activeSessions)
        assertEquals(0L, snapshot.totalSessions)
        assertEquals(0L, snapshot.totalErrors)
    }

    @Test
    fun `TunnelStats fromNative parses full array`() {
        val stats = TunnelStats.fromNative(longArrayOf(100, 2000, 50, 1000))
        assertEquals(100L, stats.txPackets)
        assertEquals(2000L, stats.txBytes)
        assertEquals(50L, stats.rxPackets)
        assertEquals(1000L, stats.rxBytes)
    }

    @Test
    fun `TunnelStats fromNative handles empty array`() {
        val stats = TunnelStats.fromNative(longArrayOf())
        assertEquals(0L, stats.txPackets)
        assertEquals(0L, stats.txBytes)
        assertEquals(0L, stats.rxPackets)
        assertEquals(0L, stats.rxBytes)
    }

    @Test
    fun `TunnelStats fromNative handles partial array`() {
        val stats = TunnelStats.fromNative(longArrayOf(10, 200))
        assertEquals(10L, stats.txPackets)
        assertEquals(200L, stats.txBytes)
        assertEquals(0L, stats.rxPackets)
        assertEquals(0L, stats.rxBytes)
    }

    @Test
    fun `TunnelStats default values are all zero`() {
        val stats = TunnelStats()
        assertEquals(0L, stats.txPackets)
        assertEquals(0L, stats.txBytes)
        assertEquals(0L, stats.rxPackets)
        assertEquals(0L, stats.rxBytes)
    }
}
