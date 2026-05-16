package com.poyka.ripdpi.services.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Core-crash test: a crash must transition to Reconnecting or Blocked — never Connected.
 */
class CoreCrashBlockedTest {
    @Test
    fun firstCrashTransitionsToReconnecting() {
        val handler = CoreCrashHandler(maxRetries = 3)
        val state = handler.handleCrash()
        assertEquals(VpnLifecycleState.Reconnecting, state)
    }

    @Test
    fun crashNeverReturnsConnected() {
        val handler = CoreCrashHandler(maxRetries = 3)
        repeat(5) {
            val state = handler.handleCrash()
            assertNotEquals(
                "Crash must never return Connected",
                VpnLifecycleState.Connected,
                state,
            )
        }
    }

    @Test
    fun crashBeyondMaxRetriesTransitionsToBlocked() {
        val handler = CoreCrashHandler(maxRetries = 2)
        handler.handleCrash() // 1 → Reconnecting
        handler.handleCrash() // 2 → Reconnecting
        val state = handler.handleCrash() // 3 → Blocked
        assertEquals(VpnLifecycleState.Blocked(BlockedReason.CoreCrash), state)
    }

    @Test
    fun reconnectingStateIsNotConnected() {
        val handler = CoreCrashHandler(maxRetries = 3)
        val state = handler.handleCrash()
        assertNotEquals(VpnLifecycleState.Connected, state)
    }

    @Test
    fun resetAllowsReconnectingAgain() {
        val handler = CoreCrashHandler(maxRetries = 1)
        handler.handleCrash() // 1 → Reconnecting
        handler.handleCrash() // 2 → Blocked
        handler.reset()
        val state = handler.handleCrash() // reset → 1 again → Reconnecting
        assertEquals(VpnLifecycleState.Reconnecting, state)
    }

    @Test
    fun crashCountIncreasesWithEachCrash() {
        val handler = CoreCrashHandler(maxRetries = 5)
        handler.handleCrash()
        handler.handleCrash()
        assertEquals(2, handler.currentCrashCount)
    }
}
