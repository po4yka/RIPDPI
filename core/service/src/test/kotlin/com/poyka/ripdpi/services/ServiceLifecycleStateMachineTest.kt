package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleStateMachineTest {
    @Test
    fun duplicateStartsAreIgnoredUntilStopCompletes() {
        val stateMachine = ServiceLifecycleStateMachine()

        assertTrue(stateMachine.tryBeginStart())
        assertEquals(ServiceLifecycleStateMachine.State.STARTING, stateMachine.state)

        assertFalse(stateMachine.tryBeginStart())

        stateMachine.markStarted()
        assertEquals(ServiceLifecycleStateMachine.State.RUNNING, stateMachine.state)
        assertFalse(stateMachine.tryBeginStart())

        stateMachine.beginStop()
        assertEquals(ServiceLifecycleStateMachine.State.STOPPING, stateMachine.state)
        assertFalse(stateMachine.tryBeginStart())

        stateMachine.markStopped()
        assertEquals(ServiceLifecycleStateMachine.State.STOPPED, stateMachine.state)
        assertTrue(stateMachine.tryBeginStart())
    }

    @Test
    fun failedStartReturnsToStoppedState() {
        val stateMachine = ServiceLifecycleStateMachine()

        assertTrue(stateMachine.tryBeginStart())

        stateMachine.markStartFailed()

        assertEquals(ServiceLifecycleStateMachine.State.STOPPED, stateMachine.state)
        assertTrue(stateMachine.tryBeginStart())
    }
}
