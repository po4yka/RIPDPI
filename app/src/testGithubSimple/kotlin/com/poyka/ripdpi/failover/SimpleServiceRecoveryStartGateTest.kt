package com.poyka.ripdpi.failover

import com.poyka.ripdpi.AppStartupReadiness
import com.poyka.ripdpi.AppStartupReadinessState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleServiceRecoveryStartGateTest {
    @Test
    fun `sticky recovery waits for successful simple startup`() =
        runTest {
            val readiness = MutableStartupReadiness()
            val result = async { SimpleServiceRecoveryStartGate(readiness).awaitReady() }

            runCurrent()
            assertFalse(result.isCompleted)

            readiness.state.value = AppStartupReadinessState.Ready
            assertTrue(result.await())
        }

    @Test
    fun `failed simple startup rejects sticky recovery`() =
        runTest {
            val readiness = MutableStartupReadiness()
            val result = async { SimpleServiceRecoveryStartGate(readiness).awaitReady() }

            readiness.state.value = AppStartupReadinessState.Failed

            assertFalse(result.await())
        }

    private class MutableStartupReadiness : AppStartupReadiness {
        val state = MutableStateFlow(AppStartupReadinessState.Pending)
        override val readiness: StateFlow<AppStartupReadinessState> = state

        override fun retryRecovery() = Unit
    }
}
