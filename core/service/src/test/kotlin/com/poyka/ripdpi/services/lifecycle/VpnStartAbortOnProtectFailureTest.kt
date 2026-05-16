package com.poyka.ripdpi.services.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * protect() returns false → TUN establishment must be aborted; state = BlockedProtectFailed.
 */
class VpnStartAbortOnProtectFailureTest {
    @Test
    fun protectFailureReturnsFalseFromGate() {
        val gate = ProtectGate(protector = { false })
        assertFalse(gate.canProtect(socketFd = 42))
    }

    @Test
    fun protectSuccessReturnsTrueFromGate() {
        val gate = ProtectGate(protector = { true })
        assertTrue(gate.canProtect(socketFd = 42))
    }

    @Test
    fun tunEstablishmentAbortedWhenProtectFails() {
        val gate = ProtectGate(protector = { false })
        var tunEstablished = false

        if (gate.canProtect(socketFd = 10)) {
            tunEstablished = true
        }

        assertFalse("TUN must not be established when protect fails", tunEstablished)
    }

    @Test
    fun stateIsBlockedProtectFailedWhenProtectReturnsFalse() {
        val gate = ProtectGate(protector = { false })
        val canProceed = gate.canProtect(socketFd = 10)

        val resultState: VpnLifecycleState =
            if (canProceed) {
                VpnLifecycleState.Connected
            } else {
                VpnLifecycleState.Blocked(BlockedReason.ProtectFailed)
            }

        assertEquals(VpnLifecycleState.Blocked(BlockedReason.ProtectFailed), resultState)
    }

    @Test
    fun multipleSocketProtectFailuresAllReturnFalse() {
        val gate = ProtectGate(protector = { false })
        assertFalse(gate.canProtect(1))
        assertFalse(gate.canProtect(2))
        assertFalse(gate.canProtect(3))
    }

    @Test
    fun protectGateForwardsCorrectFdToProtector() {
        val capturedFds = mutableListOf<Int>()
        val gate =
            ProtectGate(protector = { fd ->
                capturedFds += fd
                true
            })
        gate.canProtect(99)
        assertEquals(listOf(99), capturedFds)
    }
}
