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
class Tun2SocksTunnelLockingTest {
    private val json = Json

    @Test
    fun telemetryDoesNotBlockStop() =
        runTest {
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeTun2SocksBindings().apply {
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "tunnel", state = "running", health = "healthy"),
                        )
                }
            val tunnel = Tun2SocksTunnel(bindings)
            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42)

            val telemetryJob = async { tunnel.telemetry() }
            assertEquals(1L, telemetryStarted.await())

            val stopJob = async { tunnel.stop() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(destroyed.isCompleted)

            telemetryBlocker.complete(Unit)

            assertEquals("running", telemetryJob.await().state)
            stopJob.await()
            assertEquals(1L, destroyed.await())
        }

    @Test
    fun statsConcurrentWithTelemetry() =
        runTest {
            val statsStarted = CompletableDeferred<Long>()
            val statsBlocker = CompletableDeferred<Unit>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val bindings =
                FakeTun2SocksBindings().apply {
                    nativeStats = longArrayOf(1, 2, 3, 4)
                    statsStartedSignal = statsStarted
                    this.statsBlocker = statsBlocker
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "tunnel", state = "running", health = "healthy"),
                        )
                }
            val tunnel = Tun2SocksTunnel(bindings)
            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42)

            val statsJob = async { tunnel.stats() }
            val telemetryJob = async { tunnel.telemetry() }

            assertEquals(1L, statsStarted.await())
            assertEquals(1L, telemetryStarted.await())
            assertFalse(statsJob.isCompleted)
            assertFalse(telemetryJob.isCompleted)

            statsBlocker.complete(Unit)
            telemetryBlocker.complete(Unit)

            assertEquals(1L, statsJob.await().txPackets)
            assertEquals("running", telemetryJob.await().state)
        }

    @Test
    fun stopWaitsForInFlightStats() =
        runTest {
            val statsStarted = CompletableDeferred<Long>()
            val statsBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeTun2SocksBindings().apply {
                    nativeStats = longArrayOf(5, 6, 7, 8)
                    statsStartedSignal = statsStarted
                    this.statsBlocker = statsBlocker
                    destroySignal = destroyed
                }
            val tunnel = Tun2SocksTunnel(bindings)
            tunnel.start(Tun2SocksConfig(socks5Port = 1080), tunFd = 42)

            val statsJob = async { tunnel.stats() }
            assertEquals(1L, statsStarted.await())

            val stopJob = async { tunnel.stop() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(destroyed.isCompleted)

            statsBlocker.complete(Unit)

            assertEquals(5L, statsJob.await().txPackets)
            stopJob.await()
            assertEquals(1L, destroyed.await())
        }
}
