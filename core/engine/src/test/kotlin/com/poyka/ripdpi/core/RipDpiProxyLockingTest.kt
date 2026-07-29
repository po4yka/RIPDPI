package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Locking-model tests for [RipDpiProxy] after the migration from a single
 * `Mutex` to [com.poyka.ripdpi.core.lifetime.HandleReservation]: concurrent
 * read-style calls overlap, lifecycle transitions drain in-flight reservations
 * before retiring the handle, and cancellation never leaks a reservation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RipDpiProxyLockingTest {
    private val json = Json

    private fun prefs(port: Int): RipDpiProxyPreferences =
        RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = port))

    private fun runningTelemetryJson(): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(source = "proxy", state = "running", health = "healthy"),
        )

    @Test
    fun telemetryAndSnapshotUpdateOverlapWhenBindingsAllow() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val updateStarted = CompletableDeferred<Long>()
            val updateBlocker = CompletableDeferred<Unit>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    updateStartedSignal = updateStarted
                    this.updateBlocker = updateBlocker
                    telemetryJson = runningTelemetryJson()
                }
            val proxy = RipDpiProxy(bindings)

            val startJob = async { proxy.startProxy(prefs(1400)) }
            assertEquals(1L, started.await())

            val telemetryJob = async { proxy.pollTelemetry() }
            val updateJob = async { proxy.updateNetworkSnapshot(NativeNetworkSnapshot(transport = "wifi")) }

            // Both JNI calls enter while the other is still blocked: the old
            // single-mutex model could only admit one at a time.
            assertEquals(1L, telemetryStarted.await())
            assertEquals(1L, updateStarted.await())

            telemetryBlocker.complete(Unit)
            updateBlocker.complete(Unit)
            assertEquals("running", telemetryJob.await().state)
            updateJob.await()

            proxy.stopProxy()
            assertEquals(0, startJob.await())
        }

    @Test
    fun stopProxyWaitsForInFlightTelemetryBeforeDestroy() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson = runningTelemetryJson()
                }
            val proxy = RipDpiProxy(bindings)

            val startJob = async { proxy.startProxy(prefs(1401)) }
            assertEquals(1L, started.await())

            val telemetryJob = async { proxy.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())

            val stopJob = async { proxy.stopProxy() }
            runCurrent()

            // stop drains the in-flight telemetry reservation: it must not have
            // completed, and the handle must not have been destroyed yet.
            assertFalse(stopJob.isCompleted)
            assertFalse(destroyed.isCompleted)

            telemetryBlocker.complete(Unit)

            assertEquals("running", telemetryJob.await().state)
            stopJob.await()
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun newReservationsRejectedWhileStopDraining() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson = runningTelemetryJson()
                }
            val proxy = RipDpiProxy(bindings)

            val startJob = async { proxy.startProxy(prefs(1402)) }
            assertEquals(1L, started.await())

            // Telemetry A reserves the handle and blocks inside the JNI call.
            val telemetryA = async { proxy.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())

            // stopProxy begins draining and waits for telemetry A.
            val stopJob = async { proxy.stopProxy() }
            runCurrent()
            assertFalse(stopJob.isCompleted)

            // A fresh telemetry call is rejected while draining: it yields the
            // idle snapshot and never reaches the JNI binding.
            val rejected = proxy.pollTelemetry()
            assertEquals(NativeRuntimeSnapshot.idle(source = "proxy"), rejected)
            assertEquals(1, bindings.telemetryHandles.size)

            telemetryBlocker.complete(Unit)
            assertEquals("running", telemetryA.await().state)
            stopJob.await()
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun forwardingEvidenceUsesActiveHandleAndDefaultsWhenUnavailable() =
        runTest {
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    forwardingEvidenceJson =
                        """
                        {
                          "proxyClientSocketsAccepted": 1,
                          "upstreamSocketCreated": 2,
                          "upstreamOpened": 1,
                          "upstreamOpenFailures": 1,
                          "protectAttempted": 2,
                          "protectSucceeded": 1,
                          "protectRejected": 1,
                          "upstreamApplicationBytes": 256,
                          "firstUpstreamApplicationForwardedAt": 100,
                          "lastUpstreamApplicationForwardedAt": 200
                        }
                        """.trimIndent()
                }
            val proxy = RipDpiProxy(bindings)

            assertEquals(ProxyForwardingEvidence.Empty, proxy.pollForwardingEvidence())

            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            bindings.startedSignal = started
            bindings.startBlocker = startBlocker
            bindings.stopCompletesStartBlocker = true
            val startJob = async { proxy.startProxy(prefs(1410)) }
            assertEquals(1L, started.await())

            val evidence = proxy.pollForwardingEvidence()

            assertEquals(1L, bindings.forwardingEvidenceHandles.single())
            assertEquals(1L, evidence.proxyClientSocketsAccepted)
            assertEquals(2L, evidence.upstreamSocketCreated)
            assertEquals(1L, evidence.upstreamOpened)
            assertEquals(1L, evidence.upstreamOpenFailures)
            assertEquals(2L, evidence.protectAttempted)
            assertEquals(1L, evidence.protectSucceeded)
            assertEquals(1L, evidence.protectRejected)
            assertEquals(256L, evidence.upstreamApplicationBytes)
            assertEquals(100L, evidence.firstUpstreamApplicationForwardedAt)
            assertEquals(200L, evidence.lastUpstreamApplicationForwardedAt)

            proxy.stopProxy()
            assertEquals(0, startJob.await())
        }

    @Test
    fun startStopStartAcquiresNewHandle() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    createdHandle = 1L
                }
            val proxy = RipDpiProxy(bindings)

            // Session 1 runs on handle 1 until stopProxy drains and retires it.
            val firstSession = async { proxy.startProxy(prefs(1403)) }
            assertEquals(1L, started.await())
            proxy.stopProxy()
            assertEquals(0, firstSession.await())

            // Session 2 must create and start a fresh handle. The start blocker
            // is already released, so this session self-completes.
            bindings.createdHandle = 2L
            assertEquals(0, proxy.startProxy(prefs(1403)))

            assertEquals(listOf(1L, 2L), bindings.startedHandles)
            assertEquals(listOf(1L, 2L), bindings.destroyedHandles)
            assertEquals(2, bindings.createdPayloads.size)
        }

    @Test
    fun cancelledTelemetryReleasesReservationForStop() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson = runningTelemetryJson()
                }
            val proxy = RipDpiProxy(bindings)

            val startJob = async { proxy.startProxy(prefs(1404)) }
            assertEquals(1L, started.await())

            // Cancel the telemetry caller while its reservation is in flight.
            val telemetryJob = async { proxy.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())
            telemetryJob.cancel()
            telemetryBlocker.complete(Unit)
            telemetryJob.join()

            // The reservation was released despite cancellation, so stopProxy
            // drains immediately and retires the handle instead of wedging.
            proxy.stopProxy()
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }
}
