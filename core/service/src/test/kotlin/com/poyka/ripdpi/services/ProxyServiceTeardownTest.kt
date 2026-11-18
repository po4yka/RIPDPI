package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProxyServiceTeardownTest {
    @Test
    fun `replacement waits until old proxy retries and releases its resources`() =
        runBlocking {
            val old = Robolectric.buildService(RipDpiProxyService::class.java)
            val replacement = Robolectric.buildService(RipDpiProxyService::class.java)
            for (controller in listOf(old, replacement)) {
                initializePausedProxyService(controller.get(), PausedServiceReconstructionFixture(Mode.Proxy))
                controller.create()
            }
            val attempted = CompletableDeferred<Unit>()
            val permitRelease = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val graphReached = IllegalStateException("replacement reached its session factory")
            replacement.get().sessionComponentBuilderProvider =
                Provider {
                    check(released.isCompleted) { "old proxy still owns resources" }
                    throw graphReached
                }
            ReflectionHelpers.getField<ActiveSessionOwnership>(old.get(), "activeOwnership").own {
                attempted.complete(Unit)
                if (!permitRelease.isCompleted) throw RuntimeCleanupPendingException()
                released.complete(Unit)
            }
            old.destroy()
            try {
                withTimeout(5_000) { attempted.await() }
                val acquisition =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { attachPausedServiceSession(replacement.get()) }.exceptionOrNull()
                    }
                assertFalse(acquisition.isCompleted)
                permitRelease.complete(Unit)
                assertSame(graphReached, withTimeout(5_000) { acquisition.await() })
                assertTrue(released.isCompleted)
            } finally {
                permitRelease.complete(Unit)
                awaitPausedServiceDestruction(old.get())
                replacement.destroy()
                awaitPausedServiceDestruction(replacement.get())
            }
        }
}
