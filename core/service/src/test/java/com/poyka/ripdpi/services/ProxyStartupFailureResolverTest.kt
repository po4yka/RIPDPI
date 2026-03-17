package com.poyka.ripdpi.services

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStartupFailureResolverTest {
    @Test
    fun returnsReadinessErrorWhenProxyStartWasStillActive() {
        val readinessError = IllegalStateException("Timed out waiting for proxy readiness")

        val resolved =
            resolveProxyStartupFailure(
                readinessError = readinessError,
                proxyStartWasActive = true,
                proxyStartResult = Result.success(0),
            )

        assertSame(readinessError, resolved)
    }

    @Test
    fun returnsNativeStartupFailureWhenProxyExitedBeforeReady() {
        val startupFailure = IOException("listener bind failed")

        val resolved =
            resolveProxyStartupFailure(
                readinessError = IllegalStateException("Proxy is not running"),
                proxyStartWasActive = false,
                proxyStartResult = Result.failure(startupFailure),
            )

        assertSame(startupFailure, resolved)
    }

    @Test
    fun reportsGenericEarlyExitWhenProxyExitedCleanlyBeforeReady() {
        val readinessError = IllegalStateException("Proxy is not running")

        val resolved =
            resolveProxyStartupFailure(
                readinessError = readinessError,
                proxyStartWasActive = false,
                proxyStartResult = Result.success(0),
            )

        assertTrue(resolved is IllegalStateException)
        assertEquals("Proxy exited before becoming ready", resolved.message)
        assertSame(readinessError, resolved.cause)
    }

    @Test
    fun reportsExitCodeWhenProxyExitedBeforeReadyWithFailureCode() {
        val readinessError = IllegalStateException("Proxy is not running")

        val resolved =
            resolveProxyStartupFailure(
                readinessError = readinessError,
                proxyStartWasActive = false,
                proxyStartResult = Result.success(22),
            )

        assertTrue(resolved is IllegalStateException)
        assertEquals("Proxy exited with code 22 before becoming ready", resolved.message)
        assertSame(readinessError, resolved.cause)
    }
}
