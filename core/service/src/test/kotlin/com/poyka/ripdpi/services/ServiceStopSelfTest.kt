package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopSelfTest {
    @Test
    fun `fallback stop is skipped when start id stops service`() {
        val calls = StopSelfCalls(stopSelfResult = true)

        requestStopSelfWithFallback(
            stopSelfStartId = 42,
            stopSelfResult = calls::stopSelfResult,
            stopSelf = calls::stopSelf,
        )

        assertEquals(42, calls.stopSelfResultStartId)
        assertFalse(calls.stopSelfCalled)
    }

    @Test
    fun `fallback stop runs when start id does not stop service`() {
        val calls = StopSelfCalls(stopSelfResult = false)

        requestStopSelfWithFallback(
            stopSelfStartId = 42,
            stopSelfResult = calls::stopSelfResult,
            stopSelf = calls::stopSelf,
        )

        assertEquals(42, calls.stopSelfResultStartId)
        assertTrue(calls.stopSelfCalled)
    }

    @Test
    fun `fallback stop runs without start id`() {
        val calls = StopSelfCalls(stopSelfResult = true)

        requestStopSelfWithFallback(
            stopSelfStartId = null,
            stopSelfResult = calls::stopSelfResult,
            stopSelf = calls::stopSelf,
        )

        assertEquals(null, calls.stopSelfResultStartId)
        assertTrue(calls.stopSelfCalled)
    }

    private class StopSelfCalls(
        private val stopSelfResult: Boolean,
    ) {
        var stopSelfResultStartId: Int? = null
            private set
        var stopSelfCalled: Boolean = false
            private set

        fun stopSelfResult(startId: Int): Boolean {
            stopSelfResultStartId = startId
            return stopSelfResult
        }

        fun stopSelf() {
            stopSelfCalled = true
        }
    }
}
