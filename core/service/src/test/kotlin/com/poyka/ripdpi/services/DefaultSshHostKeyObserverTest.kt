package com.poyka.ripdpi.services

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `native status failures reach the service contract without changing their meaning`() =
        runTest {
            val cases =
                listOf(
                    1 to SshHostKeyObservationFailure.InvalidInput,
                    2 to SshHostKeyObservationFailure.Timeout,
                    3 to SshHostKeyObservationFailure.ConnectFailed,
                    4 to SshHostKeyObservationFailure.HandshakeFailed,
                    5 to SshHostKeyObservationFailure.ProtectionDenied,
                    99 to SshHostKeyObservationFailure.InternalFailure,
                )
            cases.forEach { (status, expected) ->
                SshObserverFixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { fixture ->
                    fixture.bindings.status = status
                    val result = async { fixture.observer.observe("127.0.0.1", 22) }
                    runCurrent()
                    fixture.publishUnderlay()
                    runCurrent()

                    assertEquals(SshHostKeyObservation.Failed(expected), result.await())
                    assertEquals(1, fixture.bindings.calls)
                    assertTrue(fixture.connectivity.networkCallbacks.isEmpty())
                    assertTrue(fixture.application.boundServiceConnections.isEmpty())
                }
            }
        }

    @Test
    fun `busy observation reports the service failure while the first request completes`() =
        runTest {
            SshObserverFixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { fixture ->
                val first = async { fixture.observer.observe("127.0.0.1", 22) }
                runCurrent()
                assertEquals(
                    SshHostKeyObservation.Failed(SshHostKeyObservationFailure.Busy),
                    fixture.observer.observe("127.0.0.1", 22),
                )
                fixture.publishUnderlay()
                runCurrent()
                assertEquals(SshHostKeyObservation.Observed(fixture.bindings.fingerprint, "ssh-ed25519"), first.await())
            }
        }

    @Test
    fun `missing underlay reaches the service failure without invoking native code`() =
        runTest {
            SshObserverFixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { fixture ->
                val result = async { fixture.observer.observe("127.0.0.1", 22) }
                runCurrent()
                advanceTimeBy(10_001)
                runCurrent()
                assertEquals(SshHostKeyObservation.Failed(SshHostKeyObservationFailure.NoUnderlay), result.await())
                assertEquals(0, fixture.bindings.calls)
                assertTrue(fixture.connectivity.networkCallbacks.isEmpty())
                assertTrue(fixture.application.boundServiceConnections.isEmpty())
            }
        }

    @Test
    fun `revoked network reaches the service failure without invoking native code`() =
        runTest {
            SshObserverFixture(backgroundScope, StandardTestDispatcher(testScheduler)).use { fixture ->
                val result = async { fixture.observer.observe("127.0.0.1", 22) }
                runCurrent()
                val network = fixture.publishUnderlay()
                fixture.resolver.beforeReturn = {
                    fixture.connectivity.networkCallbacks
                        .single()
                        .onLost(network)
                }
                runCurrent()
                assertEquals(SshHostKeyObservation.Failed(SshHostKeyObservationFailure.NetworkChanged), result.await())
                assertEquals(0, fixture.bindings.calls)
                assertTrue(fixture.connectivity.networkCallbacks.isEmpty())
                assertTrue(fixture.application.boundServiceConnections.isEmpty())
            }
        }

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
                    SshHostKeyObservation.Observed(fixture.bindings.fingerprint, "ssh-ed25519"),
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
                lateinit var request: Deferred<SshHostKeyObservation>
                fixture.resolver.beforeReturn = { request.cancel() }
                request = async { fixture.observer.observe("127.0.0.1", 22) }
                runCurrent()
                fixture.publishUnderlay()
                runCurrent()

                assertEquals(1 to 0, fixture.resolver.calls to fixture.bindings.calls)
            }
        }
}
