package com.poyka.ripdpi.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun stopFailureStillReturnsLifecycleToStoppedState() =
        runTest {
            val stateMachine =
                ServiceLifecycleStateMachine().apply {
                    tryBeginStart()
                    markStarted()
                }
            var stopping = false
            val runner =
                RuntimeLifecycleRunner(
                    mutex = Mutex(),
                    lifecycleState = stateMachine,
                    serviceLabel = { "test" },
                    isStopping = { stopping },
                    setStopping = { stopping = it },
                )

            val failure = runCatching { runner.stop { error("cleanup failed") } }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(stopping)
            assertEquals(ServiceLifecycleStateMachine.State.STOPPED, stateMachine.state)
            assertEquals(null, runner.start {})
            assertEquals(ServiceLifecycleStateMachine.State.RUNNING, stateMachine.state)
        }
}
