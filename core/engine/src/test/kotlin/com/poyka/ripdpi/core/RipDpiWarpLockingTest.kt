package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
