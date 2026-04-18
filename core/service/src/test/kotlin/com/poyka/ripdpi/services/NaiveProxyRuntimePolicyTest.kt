package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaiveProxyRuntimePolicyTest {
    @Test
    fun `clean exit is not restarted`() {
        val decision = naiveProxyRestartDecision(exitCode = 0, lastFailureClass = null)

        assertFalse(decision.shouldRestart)
        assertEquals("clean_exit", decision.reasonLabel)
    }

    @Test
    fun `auth and config failures do not restart`() {
        listOf("auth", "http_connect", "tls", "config").forEach { failureClass ->
            val decision = naiveProxyRestartDecision(exitCode = 1, lastFailureClass = failureClass)

            assertFalse("expected $failureClass to be terminal", decision.shouldRestart)
            assertEquals(failureClass, decision.reasonLabel)
        }
    }

    @Test
    fun `dns failures restart with slower delay`() {
        val decision = naiveProxyRestartDecision(exitCode = 1, lastFailureClass = "dns")

        assertTrue(decision.shouldRestart)
        assertEquals(1_500L, decision.delayMillis)
        assertEquals("dns", decision.reasonLabel)
    }

    @Test
    fun `transport and unknown failures stay retryable`() {
        listOf("connect", "runtime", "helper_exit", null).forEach { failureClass ->
            val decision = naiveProxyRestartDecision(exitCode = 2, lastFailureClass = failureClass)

            assertTrue("expected $failureClass to remain retryable", decision.shouldRestart)
        }
    }
}
