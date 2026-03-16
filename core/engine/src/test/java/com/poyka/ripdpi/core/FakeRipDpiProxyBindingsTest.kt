package com.poyka.ripdpi.core

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRipDpiProxyBindingsTest {
    @Test
    fun blockedStartFailsFastInsteadOfHanging() {
        val bindings =
            FakeRipDpiProxyBindings().apply {
                startBlocker = CompletableDeferred()
                startBlockTimeoutMillis = 1L
            }

        val error =
            runCatching {
                bindings.start(handle = 1L)
            }.exceptionOrNull()

        assertTrue(error is AssertionError)
    }
}
