package com.poyka.ripdpi.diagnostics

import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkTransitionTimelineTest {
    @Test
    fun `session gate cannot deactivate until an admitted callback is enqueued`() {
        val gate = NetworkTransitionSessionGate()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        gate.activate("session-a")

        try {
            val capture =
                executor.submit<Unit> {
                    gate.withAdmission { admission ->
                        assertEquals("session-a", admission?.connectionSessionId)
                        callbackEntered.countDown()
                        releaseCallback.await()
                    }
                }
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
            val deactivate = executor.submit(gate::deactivate)

            val timeout = runCatching { deactivate.get(50, TimeUnit.MILLISECONDS) }.exceptionOrNull()
            assertTrue(timeout is TimeoutException)
            releaseCallback.countDown()

            capture.get(1, TimeUnit.SECONDS)
            deactivate.get(1, TimeUnit.SECONDS)
            gate.withAdmission { admission -> assertEquals(null, admission) }
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `deactivation cannot overtake generation recovery admitted by a public callback`() =
        runTest {
            val gate = NetworkTransitionSessionGate()
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val blockClock = AtomicBoolean(false)
            val events = mutableListOf<NetworkTransitionEvent>()
            val executor = Executors.newFixedThreadPool(2)
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = {
                        if (blockClock.get()) {
                            callbackEntered.countDown()
                            releaseCallback.await()
                        }
                        NetworkTransitionTimestamp(elapsedRealtimeMs = 5L, epochMs = 10_005L)
                    },
                    withSessionAdmission = gate::withAdmission,
                    persist = { events += it },
                )
            val networkKey = Any()
            gate.activate("session-a")
            timeline.recordAvailable(networkKey)
            timeline.recordCapabilities(networkKey, nonVpnCapabilities(validated = false))
            runCurrent()
            blockClock.set(true)

            try {
                val recovery =
                    executor.submit {
                        timeline.recordCapabilities(networkKey, nonVpnCapabilities(validated = true))
                    }
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
                val deactivate = executor.submit<NetworkTransitionAdmission?>(gate::deactivate)

                val timeout = runCatching { deactivate.get(50, TimeUnit.MILLISECONDS) }.exceptionOrNull()
                assertTrue(timeout is TimeoutException)
                releaseCallback.countDown()
                recovery.get(1, TimeUnit.SECONDS)
                val admission = requireNotNull(deactivate.get(1, TimeUnit.SECONDS))
                val seal = async { timeline.flush(admission) }
                runCurrent()

                assertTrue(seal.await())
                assertEquals(NetworkTransitionState.Present, events.last().validated)
            } finally {
                releaseCallback.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `flush persists preceding callbacks before reporting a healthy seal`() =
        runTest {
            val events = mutableListOf<NetworkTransitionEvent>()
            val timeline = timeline { event -> events += event }
            val networkKey = Any()

            timeline.recordAvailable(networkKey)
            timeline.recordCapabilities(networkKey, vpnCapabilities())
            val seal = async { timeline.flush(TestAdmission) }
            runCurrent()

            assertTrue(seal.await())
            assertEquals(
                listOf(NetworkTransitionKind.Available, NetworkTransitionKind.CapabilitiesChanged),
                events.map(NetworkTransitionEvent::kind),
            )
        }

    @Test
    fun `flush reports persistence failure and resets health after the barrier`() =
        runTest {
            var shouldFail = true
            val timeline =
                timeline {
                    if (shouldFail) {
                        shouldFail = false
                        error("injected persistence failure")
                    }
                }

            timeline.recordAvailable(Any())
            val failedSeal = async { timeline.flush(TestAdmission) }
            runCurrent()
            val recoveredSeal = async { timeline.flush(TestAdmission) }
            runCurrent()

            assertFalse(failedSeal.await())
            assertTrue(recoveredSeal.await())
        }

    @Test
    fun `flush times out while a preceding callback is still in flight`() =
        runTest {
            val releasePersist = CompletableDeferred<Unit>()
            val timeline = timeline { releasePersist.await() }

            timeline.recordAvailable(Any())
            val seal = async { timeline.flush(TestAdmission, timeoutMillis = 10L) }
            runCurrent()
            advanceTimeBy(11L)
            runCurrent()

            assertFalse(seal.await())
            releasePersist.complete(Unit)
            runCurrent()
        }

    @Test
    fun `timed out barrier cannot clear capture failure from a later session epoch`() =
        runTest {
            val gate = NetworkTransitionSessionGate()
            val releaseFirstPersist = CompletableDeferred<Unit>()
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = { NetworkTransitionTimestamp(elapsedRealtimeMs = 5L, epochMs = 10_005L) },
                    withSessionAdmission = gate::withAdmission,
                    persist = { event ->
                        if (event.connectionSessionId == "session-a") releaseFirstPersist.await()
                    },
                )
            val firstKey = Any()
            gate.activate("session-a")
            timeline.recordAvailable(firstKey)
            val firstAdmission = requireNotNull(gate.deactivate())
            val firstSeal = async { timeline.flush(firstAdmission, timeoutMillis = 10L) }
            runCurrent()
            advanceTimeBy(11L)
            runCurrent()
            assertFalse(firstSeal.await())

            val secondAdmission = gate.activate("session-b")
            val secondKey = Any()
            timeline.recordAvailable(secondKey)
            repeat(MaxPersistedNetworkTransitionsPerSession + 2) {
                timeline.recordCapabilities(secondKey, vpnCapabilities())
            }
            assertEquals(secondAdmission, gate.deactivate())

            releaseFirstPersist.complete(Unit)
            runCurrent()
            val secondSeal = async { timeline.flush(secondAdmission) }
            runCurrent()

            assertFalse(secondSeal.await())
        }

    @Test
    fun `timed out barrier cannot acknowledge failures from a restored epoch retry`() =
        runTest {
            val gate = NetworkTransitionSessionGate()
            val releaseFirstPersist = CompletableDeferred<Unit>()
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = { NetworkTransitionTimestamp(elapsedRealtimeMs = 5L, epochMs = 10_005L) },
                    withSessionAdmission = gate::withAdmission,
                    persist = { event ->
                        if (event.sequence == 1L) releaseFirstPersist.await()
                    },
                )
            val admission = gate.activate("session-a")
            val networkKey = Any()
            timeline.recordAvailable(networkKey)
            assertEquals(admission, gate.deactivate())
            val timedOutSeal = async { timeline.flush(admission, timeoutMillis = 10L) }
            runCurrent()
            advanceTimeBy(11L)
            runCurrent()
            assertFalse(timedOutSeal.await())

            gate.restore(admission)
            repeat(MaxPersistedNetworkTransitionsPerSession + 2) {
                timeline.recordCapabilities(networkKey, vpnCapabilities())
            }
            assertEquals(admission, gate.deactivate())
            releaseFirstPersist.complete(Unit)
            runCurrent()
            val retrySeal = async { timeline.flush(admission) }
            runCurrent()

            assertFalse(retrySeal.await())
        }

    @Test
    fun `timeline keeps ordered redacted callback facts and rotates generation after loss`() =
        runTest {
            val events = mutableListOf<NetworkTransitionEvent>()
            var tick = 0L
            var admission: NetworkTransitionAdmission? = TestAdmission
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = {
                        tick += 1
                        NetworkTransitionTimestamp(elapsedRealtimeMs = tick, epochMs = 10_000L + tick)
                    },
                    withSessionAdmission = { block -> block(admission) },
                    persist = { events += it },
                )
            val hostileKey =
                object {
                    override fun toString() = "wlan0/198.51.100.7/secret.example"
                }

            timeline.recordAvailable(hostileKey)
            timeline.recordCapabilities(hostileKey, vpnCapabilities())
            timeline.recordLinkProperties(hostileKey)
            timeline.recordLosing(hostileKey, 500)
            timeline.recordLost(hostileKey)
            timeline.recordLost(hostileKey)
            timeline.recordAvailable(hostileKey)
            admission = NetworkTransitionAdmission("session-b", 2L)
            runCurrent()

            assertEquals((1L..6L).toList(), events.map(NetworkTransitionEvent::sequence))
            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 2L), events.map(NetworkTransitionEvent::generation))
            assertEquals(NetworkTransitionPath.Vpn, events[1].path)
            assertEquals(NetworkTransitionState.Present, events[1].internet)
            assertEquals(NetworkTransitionState.Absent, events[1].validated)
            assertEquals(NetworkLosingDeadlineBand.Imminent, events[3].losingDeadlineBand)
            assertTrue(events.all { it.connectionSessionId == "session-a" })
            events.map(NetworkTransitionEvent::toRedactedMessage).forEach { message ->
                assertFalse(message.contains("elapsed_ms"))
                listOf("wlan0", "198.51.100.7", "secret.example").forEach { forbidden ->
                    assertFalse(message.contains(forbidden, ignoreCase = true))
                }
            }
        }

    @Test
    fun `capture-time correlation follows the active session for every callback and stays bounded`() =
        runTest {
            val events = mutableListOf<NetworkTransitionEvent>()
            var admission: NetworkTransitionAdmission? = null
            val timeline =
                NetworkTransitionTimeline(
                    scope = backgroundScope,
                    clock = { NetworkTransitionTimestamp(elapsedRealtimeMs = 5L, epochMs = 10_005L) },
                    withSessionAdmission = { block -> block(admission) },
                    persist = { events += it },
                )
            val networkKey = Any()

            timeline.recordAvailable(networkKey)
            admission = TestAdmission
            timeline.recordCapabilities(networkKey, vpnCapabilities())
            admission = NetworkTransitionAdmission("session-b", 2L)
            runCurrent()
            assertEquals(1, events.size)
            assertEquals("session-a", events.single().connectionSessionId)
            assertEquals(1L, events.single().generation)

            timeline.recordCapabilities(networkKey, vpnCapabilities())
            timeline.recordLost(networkKey)
            timeline.recordAvailable(networkKey)
            timeline.recordLinkProperties(networkKey)
            repeat(MaxPersistedNetworkTransitionsPerSession + 10) {
                timeline.recordCapabilities(networkKey, vpnCapabilities())
            }
            val seal = async { timeline.flush(requireNotNull(admission)) }
            runCurrent()

            assertFalse(seal.await())
            assertEquals(1, events.count { it.connectionSessionId == "session-a" })
            assertEquals(
                MaxPersistedNetworkTransitionsPerSession,
                events.count { it.connectionSessionId == "session-b" },
            )
            val sessionBEvents = events.filter { it.connectionSessionId == "session-b" }
            assertTrue(
                sessionBEvents.any {
                    it.kind == NetworkTransitionKind.CapabilitiesChanged && it.generation == 1L
                },
            )
            assertTrue(
                sessionBEvents.any {
                    it.kind == NetworkTransitionKind.Lost && it.generation == 1L
                },
            )
            assertTrue(sessionBEvents.any { it.kind == NetworkTransitionKind.Available && it.generation == 2L })
        }

    private fun vpnCapabilities(): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN)
        }

    private fun nonVpnCapabilities(validated: Boolean): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) addCapability(capabilities, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    private fun kotlinx.coroutines.test.TestScope.timeline(
        persist: suspend (NetworkTransitionEvent) -> Unit,
    ): NetworkTransitionTimeline =
        NetworkTransitionTimeline(
            scope = backgroundScope,
            clock = { NetworkTransitionTimestamp(elapsedRealtimeMs = 5L, epochMs = 10_005L) },
            withSessionAdmission = { block -> block(TestAdmission) },
            persist = persist,
        )

    private companion object {
        val TestAdmission = NetworkTransitionAdmission("session-a", 1L)
    }

    private fun addCapability(
        capabilities: NetworkCapabilities,
        capability: Int,
    ) {
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(capabilities, capability)
    }

    private fun addTransport(
        capabilities: NetworkCapabilities,
        transport: Int,
    ) {
        NetworkCapabilities::class
            .java
            .getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType)
            .invoke(capabilities, transport)
    }
}
