package com.poyka.ripdpi.services.leak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * onRevoke() test: verifies sockets, TUN fd, and provider runtimes close
 * in the correct deterministic order.
 */
class OnRevokeTest {
    @Test
    fun onRevokeClosesSocketFirst() {
        val calls = mutableListOf<String>()
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = { calls += "socket" },
                closeTunFd = { calls += "tun" },
                stopProviderRuntime = { calls += "runtime" },
            )
        orchestrator.onRevoke()
        assertEquals("socket", calls[0])
    }

    @Test
    fun onRevokeClosesTunFdSecond() {
        val calls = mutableListOf<String>()
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = { calls += "socket" },
                closeTunFd = { calls += "tun" },
                stopProviderRuntime = { calls += "runtime" },
            )
        orchestrator.onRevoke()
        assertEquals("tun", calls[1])
    }

    @Test
    fun onRevokeStopsProviderRuntimeLast() {
        val calls = mutableListOf<String>()
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = { calls += "socket" },
                closeTunFd = { calls += "tun" },
                stopProviderRuntime = { calls += "runtime" },
            )
        orchestrator.onRevoke()
        assertEquals("runtime", calls[2])
    }

    @Test
    fun completedStepsMatchDeclaredOrder() {
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = {},
                closeTunFd = {},
                stopProviderRuntime = {},
            )
        orchestrator.onRevoke()
        assertEquals(
            listOf(RevokeStep.SocketClosed, RevokeStep.TunFdClosed, RevokeStep.ProviderRuntimeStopped),
            orchestrator.completedSteps(),
        )
    }

    @Test
    fun resetClearsCompletedSteps() {
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = {},
                closeTunFd = {},
                stopProviderRuntime = {},
            )
        orchestrator.onRevoke()
        orchestrator.reset()
        assertTrue(orchestrator.completedSteps().isEmpty())
    }

    @Test
    fun onRevokeCallsAllThreeActions() {
        var socketClosed = false
        var tunClosed = false
        var runtimeStopped = false
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = { socketClosed = true },
                closeTunFd = { tunClosed = true },
                stopProviderRuntime = { runtimeStopped = true },
            )
        orchestrator.onRevoke()
        assertTrue(socketClosed)
        assertTrue(tunClosed)
        assertTrue(runtimeStopped)
    }
}
