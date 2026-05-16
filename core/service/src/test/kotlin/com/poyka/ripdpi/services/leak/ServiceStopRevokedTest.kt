package com.poyka.ripdpi.services.leak

import com.poyka.ripdpi.services.ServiceLifecycleStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Service-stop test: when the service stops, the state machine transitions to
 * STOPPED (revoked equivalent) and socket/TUN close is confirmed via
 * [OnRevokeOrchestrator].
 */
class ServiceStopRevokedTest {
    @Test
    fun serviceStopTransitionsStateMachineToStopped() {
        val sm = ServiceLifecycleStateMachine()
        sm.tryBeginStart()
        sm.markStarted()
        assertEquals(ServiceLifecycleStateMachine.State.RUNNING, sm.state)

        sm.beginStop()
        sm.markStopped()

        assertEquals(ServiceLifecycleStateMachine.State.STOPPED, sm.state)
    }

    @Test
    fun onRevokeClosesSocketThenTunThenRuntime() {
        val calls = mutableListOf<String>()
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = { calls += "socket" },
                closeTunFd = { calls += "tun" },
                stopProviderRuntime = { calls += "runtime" },
            )
        orchestrator.onRevoke()
        assertEquals(listOf("socket", "tun", "runtime"), calls)
    }

    @Test
    fun onRevokeRecordsAllThreeSteps() {
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = {},
                closeTunFd = {},
                stopProviderRuntime = {},
            )
        orchestrator.onRevoke()
        val steps = orchestrator.completedSteps()
        assertEquals(
            listOf(RevokeStep.SocketClosed, RevokeStep.TunFdClosed, RevokeStep.ProviderRuntimeStopped),
            steps,
        )
    }

    @Test
    fun serviceStopAfterStartFailureIsAlreadyStopped() {
        val sm = ServiceLifecycleStateMachine()
        sm.tryBeginStart()
        sm.markStartFailed()
        assertEquals(ServiceLifecycleStateMachine.State.STOPPED, sm.state)
    }

    @Test
    fun socketNotOpenAfterServiceStop() {
        var socketOpen = true
        val orchestrator =
            OnRevokeOrchestrator(
                closeSocket = { socketOpen = false },
                closeTunFd = {},
                stopProviderRuntime = {},
            )
        orchestrator.onRevoke()
        assertFalse(socketOpen)
    }
}
