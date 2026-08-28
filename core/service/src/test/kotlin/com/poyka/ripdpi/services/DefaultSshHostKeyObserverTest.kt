package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.SshHostKeyProbeResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultSshHostKeyObserverTest {
    @Test
    fun `prepared observation uses callback underlay and releases its private binding`() =
        runTest {
            SshObserverFixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { fixture ->
                val result = async { fixture.observer.observe("127.0.0.1", 22) }
                runCurrent()
                assertEquals(0, fixture.bindings.calls)
                fixture.publishUnderlay()
                runCurrent()

                assertEquals(
                    SshHostKeyProbeResult.Observed(fixture.bindings.fingerprint, "ssh-ed25519"),
                    result.await(),
                )
                assertEquals("127.0.0.1", fixture.bindings.address)
                assertTrue(fixture.connectivity.networkCallbacks.isEmpty())
                assertTrue(fixture.application.boundServiceConnections.isEmpty())
            }
        }

    @Test
    fun `cancellation during DNS prevents JNI even before caller cleanup is dispatched`() =
        runTest {
            SshObserverFixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { fixture ->
                lateinit var request: Deferred<SshHostKeyProbeResult>
                fixture.resolver.beforeReturn = { request.cancel() }
                request = async { fixture.observer.observe("127.0.0.1", 22) }
                runCurrent()
                fixture.publishUnderlay()
                runCurrent()

                assertEquals(1 to 0, fixture.resolver.calls to fixture.bindings.calls)
            }
        }
}
