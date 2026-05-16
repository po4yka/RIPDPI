package com.poyka.ripdpi.services.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression matrix covering all three failure modes:
 *   1. Startup failure (protect() returns false → TUN aborted)
 *   2. Core crash (state machine moves to Reconnecting/Blocked, never Connected)
 *   3. Revoke cleanup (all 4 resources closed in order, idempotent)
 */
class LifecycleRegressionMatrixTest {
    // ── 1. Startup failure ──────────────────────────────────────────────────

    @Test
    fun startupFailureProtectFalseBlocksTunEstablishment() {
        val gate = ProtectGate(protector = { false })
        var tunOpened = false
        if (gate.canProtect(socketFd = 5)) {
            tunOpened = true
        }
        assertFalse(tunOpened)
    }

    @Test
    fun startupFailureProducesBlockedState() {
        val gate = ProtectGate(protector = { false })
        val state: VpnLifecycleState =
            if (gate.canProtect(5)) {
                VpnLifecycleState.Connected
            } else {
                VpnLifecycleState.Blocked(BlockedReason.ProtectFailed)
            }
        assertEquals(VpnLifecycleState.Blocked(BlockedReason.ProtectFailed), state)
    }

    @Test
    fun startupSuccessAllowsTunEstablishment() {
        val gate = ProtectGate(protector = { true })
        assertTrue(gate.canProtect(socketFd = 5))
    }

    // ── 2. Core crash ───────────────────────────────────────────────────────

    @Test
    fun coreCrashNeverYieldsConnected() {
        val handler = CoreCrashHandler(maxRetries = 3)
        repeat(6) {
            val state = handler.handleCrash()
            assertNotEquals(VpnLifecycleState.Connected, state)
        }
    }

    @Test
    fun coreCrashWithinRetriesYieldsReconnecting() {
        val handler = CoreCrashHandler(maxRetries = 3)
        assertEquals(VpnLifecycleState.Reconnecting, handler.handleCrash())
    }

    @Test
    fun coreCrashExceedingRetriesYieldsBlocked() {
        val handler = CoreCrashHandler(maxRetries = 1)
        handler.handleCrash() // 1 → Reconnecting
        val state = handler.handleCrash() // 2 → Blocked
        assertEquals(VpnLifecycleState.Blocked(BlockedReason.CoreCrash), state)
    }

    // ── 3. Revoke cleanup ───────────────────────────────────────────────────

    @Test
    fun revokeClosesAllFourResourcesInOrder() {
        val calls = mutableListOf<String>()
        val flow =
            OnRevokeFlow(
                closeTunFd = { calls += "tun" },
                closeTunnelSockets = { calls += "socket" },
                stopProviderRuntime = { calls += "runtime" },
                stopLocalInbounds = { calls += "inbound" },
            )
        flow.execute()
        assertEquals(listOf("tun", "socket", "runtime", "inbound"), calls)
    }

    @Test
    fun revokeIsIdempotentOnDoubleCall() {
        val calls = mutableListOf<String>()
        val flow =
            OnRevokeFlow(
                closeTunFd = { calls += "tun" },
                closeTunnelSockets = { calls += "socket" },
                stopProviderRuntime = { calls += "runtime" },
                stopLocalInbounds = { calls += "inbound" },
            )
        flow.execute()
        flow.execute()
        assertEquals(4, calls.size)
    }

    @Test
    fun secureProfileBypassDefaultIsFalse() {
        val guard = BuilderAllowBypassGuard()
        assertFalse(guard.allow(VpnProfile()))
    }

    @Test
    fun unsafeProfileBypassIsAllowed() {
        val guard = BuilderAllowBypassGuard()
        assertTrue(guard.allow(VpnProfile(unsafeAllowBypass = true)))
    }
}
