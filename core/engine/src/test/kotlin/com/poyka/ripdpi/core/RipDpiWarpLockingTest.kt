package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class RipDpiWarpLockingTest {
    private val json = Json

    @Test
    fun telemetryDoesNotBlockStop() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiWarpBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "warp", state = "running", health = "healthy"),
                        )
                }
            val warp = RipDpiWarp(bindings)
            val startJob = async { warp.start(testWarpConfig()) }
            assertEquals(1L, started.await())

            val telemetryJob = async { warp.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())

            val stopJob = async { warp.stop() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(destroyed.isCompleted)

            telemetryBlocker.complete(Unit)

            assertEquals("running", telemetryJob.await().state)
            stopJob.await()
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun stopWaitsForInFlightTelemetry() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiWarpBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "warp", state = "running", health = "healthy"),
                        )
                }
            val warp = RipDpiWarp(bindings)
            val startJob = async { warp.start(testWarpConfig()) }
            assertEquals(1L, started.await())

            val telemetryJob = async { warp.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())

            val stopJob = async { warp.stop() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(destroyed.isCompleted)

            telemetryBlocker.complete(Unit)

            telemetryJob.await()
            stopJob.await()
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun telemetryReservationsOverlap() =
        runTest {
            val firstEnteredSignal = CompletableDeferred<Long>()
            val secondEnteredSignal = CompletableDeferred<Long>()
            val bindings =
                OverlappingWarpBindings().apply {
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "warp", state = "running", health = "healthy"),
                        )
                    telemetryEnteredSignals += firstEnteredSignal
                    telemetryEnteredSignals += secondEnteredSignal
                }
            val warp = RipDpiWarp(bindings)
            val startJob = async { warp.start(testWarpConfig()) }
            assertEquals(1L, bindings.started.await())

            val firstTelemetry = async { warp.pollTelemetry() }
            val secondTelemetry = async { warp.pollTelemetry() }

            // Both telemetry JNI calls enter while the other is still blocked:
            // pollTelemetry holds a reservation, not the exclusive section.
            assertEquals(1L, firstEnteredSignal.await())
            assertEquals(1L, secondEnteredSignal.await())

            bindings.telemetryBlocker.complete(Unit)
            assertEquals("running", firstTelemetry.await().state)
            assertEquals("running", secondTelemetry.await().state)

            warp.stop()
            assertEquals(0, startJob.await())
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
                FakeRipDpiWarpBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                }
            val warp = RipDpiWarp(bindings)
            val startJob = async { warp.start(testWarpConfig()) }
            assertEquals(1L, started.await())

            // Cancel the telemetry caller while its reservation is in flight.
            val telemetryJob = async { warp.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())
            telemetryJob.cancel()
            telemetryBlocker.complete(Unit)
            telemetryJob.join()

            // The reservation was released despite cancellation, so stop drains
            // and retires the handle instead of wedging.
            warp.stop()
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    private fun testWarpConfig(): ResolvedRipDpiWarpConfig =
        ResolvedRipDpiWarpConfig(
            enabled = true,
            profileId = "warp-profile",
            accountKind = "zero-trust",
            deviceId = "device-id",
            accessToken = "",
            privateKey = "private-key",
            publicKey = "public-key",
            peerPublicKey = "peer-public-key",
            endpoint =
                ResolvedRipDpiWarpEndpoint(
                    host = "warp.example.test",
                    ipv4 = "203.0.113.10",
                    port = 2408,
                ),
            routeMode = "full",
            routeHosts = "",
            builtInRulesEnabled = true,
            endpointSelectionMode = "manual",
            manualEndpoint = RipDpiWarpManualEndpointConfig(),
            scannerEnabled = false,
            scannerParallelism = 1,
            scannerMaxRttMs = 1_000,
            amnezia = RipDpiWarpAmneziaConfig(),
            localSocksHost = "127.0.0.1",
            localSocksPort = 1080,
        )

    /**
     * Warp binding fake whose `start` blocks until `stop`, and whose
     * `pollTelemetry` completes the next queued [telemetryEnteredSignals] entry
     * so two concurrent telemetry reservations can be observed entering the JNI
     * surface at the same time.
     */
    private class OverlappingWarpBindings : RipDpiWarpBindings {
        val started = CompletableDeferred<Long>()
        val startBlocker = CompletableDeferred<Unit>()
        val telemetryBlocker = CompletableDeferred<Unit>()
        val telemetryEnteredSignals = ConcurrentLinkedQueue<CompletableDeferred<Long>>()
        val destroyedHandles = CopyOnWriteArrayList<Long>()

        @Volatile var telemetryJson: String? = null

        override fun create(configJson: String): Long = 1L

        override fun start(handle: Long): Int {
            started.complete(handle)
            runBlocking { startBlocker.await() }
            return 0
        }

        override fun stop(handle: Long) {
            startBlocker.complete(Unit)
        }

        override fun pollTelemetry(handle: Long): String? {
            telemetryEnteredSignals.poll()?.complete(handle)
            runBlocking { telemetryBlocker.await() }
            return telemetryJson
        }

        override fun destroy(handle: Long) {
            destroyedHandles += handle
        }
    }
}
