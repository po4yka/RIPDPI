package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeNetworkSnapshot
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
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWrapperLifecycleRaceTest {
    private val json = Json

    @Test
    fun `proxy stop waits for in-flight telemetry before handle destroy`() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val telemetryStarted = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    startBlocker = blocker
                    stopCompletesStartBlocker = true
                    telemetryStartedSignal = telemetryStarted
                    this.telemetryBlocker = telemetryBlocker
                    destroySignal = destroyed
                    telemetryJson =
                        json.encodeToString(
                            NativeRuntimeSnapshot.serializer(),
                            NativeRuntimeSnapshot(source = "proxy", state = "running", health = "healthy"),
                        )
                }
            val proxy = RipDpiProxy(bindings)

            val startJob =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1300)))
                }
            assertEquals(1L, started.await())

            val telemetryJob = async { proxy.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())

            val stopJob = async { proxy.stopProxy() }
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
    fun `proxy stop waits for in-flight network snapshot update before handle destroy`() =
        runTest {
            val blocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val updateStarted = CompletableDeferred<Long>()
            val updateBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    startBlocker = blocker
                    stopCompletesStartBlocker = true
                    updateStartedSignal = updateStarted
                    this.updateBlocker = updateBlocker
                    destroySignal = destroyed
                }
            val proxy = RipDpiProxy(bindings)

            val startJob =
                async {
                    proxy.startProxy(RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = 1301)))
                }
            assertEquals(1L, started.await())

            val updateJob = async { proxy.updateNetworkSnapshot(NativeNetworkSnapshot(transport = "wifi")) }
            assertEquals(1L, updateStarted.await())

            val stopJob = async { proxy.stopProxy() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(destroyed.isCompleted)

            updateBlocker.complete(Unit)

            updateJob.await()
            stopJob.await()
            assertEquals(1L, destroyed.await())
            assertEquals(listOf(1L), bindings.updatedHandles)
            assertEquals(0, startJob.await())
        }

    @Test
    fun `relay stop waits for in-flight telemetry before handle destroy`() =
        runTest {
            val bindings = BlockingRelayBindings(source = "relay")
            val relay = RipDpiRelay(bindings)

            val startJob = async { relay.start(testRelayConfig()) }
            assertEquals(1L, bindings.started.await())

            val telemetryJob = async { relay.pollTelemetry() }
            assertEquals(1L, bindings.telemetryStarted.await())

            val stopJob = async { relay.stop() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(bindings.destroyed.isCompleted)

            bindings.telemetryBlocker.complete(Unit)

            assertEquals("running", telemetryJob.await().state)
            stopJob.await()
            assertEquals(1L, bindings.destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun `warp stop waits for in-flight telemetry before handle destroy`() =
        runTest {
            val bindings = BlockingWarpBindings(source = "warp")
            val warp = RipDpiWarp(bindings)

            val startJob = async { warp.start(testWarpConfig()) }
            assertEquals(1L, bindings.started.await())

            val telemetryJob = async { warp.pollTelemetry() }
            assertEquals(1L, bindings.telemetryStarted.await())

            val stopJob = async { warp.stop() }
            runCurrent()

            assertFalse(stopJob.isCompleted)
            assertFalse(bindings.destroyed.isCompleted)

            bindings.telemetryBlocker.complete(Unit)

            assertEquals("running", telemetryJob.await().state)
            stopJob.await()
            assertEquals(1L, bindings.destroyed.await())
            assertEquals(0, startJob.await())
        }

    private fun testRelayConfig(): ResolvedRipDpiRelayConfig =
        ResolvedRipDpiRelayConfig(
            enabled = true,
            kind = "vless",
            profileId = "relay-profile",
            server = "relay.example.test",
            serverPort = 443,
            serverName = "relay.example.test",
            realityPublicKey = "",
            realityShortId = "",
            chainEntryServer = "",
            chainEntryPort = 0,
            chainEntryServerName = "",
            chainEntryPublicKey = "",
            chainEntryShortId = "",
            chainExitServer = "",
            chainExitPort = 0,
            chainExitServerName = "",
            chainExitPublicKey = "",
            chainExitShortId = "",
            masqueUrl = "",
            masqueUseHttp2Fallback = false,
            localSocksHost = "127.0.0.1",
            localSocksPort = 1080,
            udpEnabled = false,
            tcpFallbackEnabled = true,
        )

    private fun testWarpConfig(): ResolvedRipDpiWarpConfig =
        ResolvedRipDpiWarpConfig(
            true,
            "warp-profile",
            "zero-trust",
            "device-id",
            "session-value",
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

    private abstract class BlockingTelemetryBindings(
        private val source: String,
    ) {
        val started = CompletableDeferred<Long>()
        val startBlocker = CompletableDeferred<Unit>()
        val telemetryStarted = CompletableDeferred<Long>()
        val telemetryBlocker = CompletableDeferred<Unit>()
        val destroyed = CompletableDeferred<Long>()
        private val handleCounter = AtomicLong(1L)

        protected fun nextHandle(): Long = handleCounter.getAndIncrement()

        protected fun blockOnStart(handle: Long) {
            started.complete(handle)
            startBlocker.awaitBlocking()
        }

        protected fun blockOnTelemetry(handle: Long): String {
            telemetryStarted.complete(handle)
            telemetryBlocker.awaitBlocking()
            return Json.encodeToString(
                NativeRuntimeSnapshot.serializer(),
                NativeRuntimeSnapshot(source = source, state = "running", health = "healthy"),
            )
        }

        protected fun recordDestroy(handle: Long) {
            destroyed.complete(handle)
        }

        protected fun releaseStartFromStop() {
            startBlocker.complete(Unit)
        }
    }

    private class BlockingRelayBindings(
        source: String,
    ) : BlockingTelemetryBindings(source),
        RipDpiRelayBindings {
        override fun create(configJson: String): Long = nextHandle()

        override fun start(handle: Long): Int {
            blockOnStart(handle)
            return 0
        }

        override fun stop(handle: Long) {
            releaseStartFromStop()
        }

        override fun pollTelemetry(handle: Long): String = blockOnTelemetry(handle)

        override fun destroy(handle: Long) {
            recordDestroy(handle)
        }
    }

    private class BlockingWarpBindings(
        source: String,
    ) : BlockingTelemetryBindings(source),
        RipDpiWarpBindings {
        override fun create(configJson: String): Long = nextHandle()

        override fun start(handle: Long): Int {
            blockOnStart(handle)
            return 0
        }

        override fun stop(handle: Long) {
            releaseStartFromStop()
        }

        override fun pollTelemetry(handle: Long): String = blockOnTelemetry(handle)

        override fun destroy(handle: Long) {
            recordDestroy(handle)
        }
    }

    private companion object {
        fun CompletableDeferred<Unit>.awaitBlocking() {
            kotlinx.coroutines.runBlocking {
                await()
            }
        }
    }
}
