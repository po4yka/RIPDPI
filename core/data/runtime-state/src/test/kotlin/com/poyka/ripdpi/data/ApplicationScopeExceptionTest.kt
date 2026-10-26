package com.poyka.ripdpi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApplicationScopeExceptionTest {
    @Test
    fun `an uncaught failure in an application scope child does not reach the thread crash handler`() {
        val crashes = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> crashes += error }
        val inline = Dispatchers.Unconfined
        val dispatchers = AppCoroutineDispatchers(inline, inline, inline)
        val scopes =
            listOf(
                CoroutineScopesModule.provideApplicationScope(dispatchers),
                CoroutineScopesModule.provideApplicationIoScope(dispatchers),
            )
        try {
            scopes.forEach { scope -> scope.launch { error("disk full") } }
            var siblingRan = false
            scopes.first().launch { siblingRan = true }

            assertTrue("application scope crashed the process: $crashes", crashes.isEmpty())
            assertTrue(siblingRan)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            scopes.forEach { it.cancel() }
        }
    }
}
