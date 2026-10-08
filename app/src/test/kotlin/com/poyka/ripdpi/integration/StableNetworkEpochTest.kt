package com.poyka.ripdpi.integration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StableNetworkEpochTest {
    @Test
    fun `ready token must remain unchanged for the whole quiet window`() =
        runTest {
            val changes = MutableStateFlow(1L)
            val result = async { awaitStableNetworkEpoch(changes, { changes.value }) }
            runCurrent()
            advanceTimeBy(499)
            assertFalse(result.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1L, result.await())
        }

    @Test
    fun `null resets readiness and requires a new complete quiet window`() =
        runTest {
            val changes = MutableStateFlow(0L)
            var token: Long? = null
            val result = async { awaitStableNetworkEpoch(changes, { token }) }
            runCurrent()
            advanceTimeBy(500)
            assertFalse(result.isCompleted)
            token = 1L
            changes.value++
            runCurrent()
            advanceTimeBy(450)
            token = null
            changes.value++
            runCurrent()
            advanceTimeBy(100)
            assertFalse(result.isCompleted)
            token = 2L
            changes.value++
            runCurrent()
            advanceTimeBy(499)
            assertFalse(result.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2L, result.await())
        }

    @Test
    fun `callback ABA restarts the wait even when the fingerprint returns`() =
        runTest {
            val changes = MutableStateFlow(1L)
            var fingerprint = "A"
            val result = async { awaitStableNetworkEpoch(changes, { changes.value to fingerprint }) }
            runCurrent()
            advanceTimeBy(450)
            fingerprint = "B"
            changes.value++
            fingerprint = "A"
            changes.value++
            runCurrent()
            advanceTimeBy(499)
            assertFalse(result.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3L to "A", result.await())
            assertEquals(950L, testScheduler.currentTime)
        }

    @Test
    fun `continuous callbacks exhaust the original readiness deadline`() =
        runTest {
            val changes = MutableStateFlow(0L)
            val churn =
                launch {
                    repeat(20) {
                        kotlinx.coroutines.delay(250)
                        changes.value++
                    }
                }
            var timedOut = false
            try {
                withTimeout(5_000L) { awaitStableNetworkEpoch(changes, { changes.value }) }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            } finally {
                churn.cancel()
            }
            assertTrue(timedOut)
            assertEquals(5_000L, testScheduler.currentTime)
        }
}
