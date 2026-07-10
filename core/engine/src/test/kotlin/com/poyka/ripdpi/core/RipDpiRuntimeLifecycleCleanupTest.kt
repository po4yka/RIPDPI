package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Lifecycle-cleanup edge cases for [RipDpiProxy] and [RipDpiRelay] after the
 * [com.poyka.ripdpi.core.lifetime.HandleReservation] migration.
 *
 * Two invariants are exercised here:
 * - `stopProxy` runs its drain + native-stop section non-cancellably, so a
 *   cancelled caller can never abandon cleanup once the stop path has started.
 * - A session that exits before reaching readiness clears its readiness
 *   signal, so a later `awaitReady` reports `NotRunning` instead of
 *   resurfacing the dead session's failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RipDpiRuntimeLifecycleCleanupTest {
    private val json = Json

    private fun prefs(port: Int): RipDpiProxyPreferences =
        RipDpiProxyUIPreferences(listen = RipDpiListenConfig(port = port))

    private fun runtimeReadyTelemetry(source: String): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(
                source = source,
                state = "running",
                health = "healthy",
                nativeEvents =
                    listOf(
                        NativeRuntimeEvent(
                            source = source,
                            level = "info",
                            message = "listener started",
                            createdAt = 1L,
                            kind = "runtime_ready",
                        ),
                    ),
            ),
        )

    private fun readinessSignalOf(runtime: Any): Any? =
        runtime.javaClass
            .getDeclaredField("readinessSignal")
            .apply { isAccessible = true }
            .get(runtime)

    private fun relayConfig(): ResolvedRipDpiRelayConfig =
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
            tlsFingerprintProfile = "chrome_stable",
        )

    @Test
    fun cancellingStopProxyWhileDrainingTelemetryStillStops() =
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
                    telemetryJson = runtimeReadyTelemetry("proxy")
                }
            val proxy = RipDpiProxy(bindings)

            val startJob = async { proxy.startProxy(prefs(1411)) }
            assertEquals(1L, started.await())

            // Telemetry reserves the handle and blocks inside the JNI call.
            val telemetryJob = async { proxy.pollTelemetry() }
            assertEquals(1L, telemetryStarted.await())

            // stopProxy begins draining the in-flight reservation; its caller
            // is then cancelled mid-drain.
            val stopJob = launch { proxy.stopProxy() }
            runCurrent()
            assertFalse(stopJob.isCompleted)
            stopJob.cancel()
            runCurrent()

            // The non-cancellable drain is not abandoned: native stop has not
            // run yet (telemetry still in flight) and stopProxy is not done.
            assertTrue(bindings.stoppedHandles.isEmpty())
            assertFalse(stopJob.isCompleted)

            // Telemetry drains; the cancelled stop still calls native stop and
            // startProxy unwinds and destroys the handle.
            telemetryBlocker.complete(Unit)
            assertEquals("running", telemetryJob.await().state)
            stopJob.join()

            assertEquals(listOf(1L), bindings.stoppedHandles.toList())
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun cancellingStopProxyInsideNativeStopStillRetiresHandle() =
        runTest {
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            val destroyed = CompletableDeferred<Long>()
            val bindings =
                FakeRipDpiProxyBindings().apply {
                    startedSignal = started
                    this.startBlocker = startBlocker
                    stopCompletesStartBlocker = true
                    destroySignal = destroyed
                }
            val proxy = RipDpiProxy(bindings)

            val startJob = async { proxy.startProxy(prefs(1412)) }
            assertEquals(1L, started.await())

            // The stop caller is cancelled at the exact point control is
            // inside the native stop call.
            val stopJob = launch { proxy.stopProxy() }
            bindings.onStop = { stopJob.cancel() }
            stopJob.join()

            // Cancellation landed mid-stop, yet native stop ran and the handle
            // is not wedged: startProxy unwinds and destroys it.
            assertEquals(listOf(1L), bindings.stoppedHandles.toList())
            assertEquals(1L, destroyed.await())
            assertEquals(0, startJob.await())
        }

    @Test
    fun proxySessionExitingBeforeReadyLeavesNoStaleReadinessSignal() =
        runTest {
            // No start blocker: the native session exits cleanly before ever
            // reaching readiness.
            val proxy = RipDpiProxy(FakeRipDpiProxyBindings())

            assertEquals(0, proxy.startProxy(prefs(1413)))

            // The exceptional startup signal must not linger as a stale
            // readiness signal.
            assertNull(readinessSignalOf(proxy))
            val error = runCatching { proxy.awaitReady() }.exceptionOrNull()
            assertTrue("expected NotRunning, got $error", error is NativeError.NotRunning)
        }

    @Test
    fun awaitReadyThrowsNotRunningAfterFailedProxyStartReadiness() =
        runTest {
            val bindings = FakeRipDpiProxyBindings().apply { startFailure = IOException("proxy boom") }
            val proxy = RipDpiProxy(bindings)

            val startError = runCatching { proxy.startProxy(prefs(1414)) }.exceptionOrNull()
            assertTrue("expected IOException, got $startError", startError is IOException)

            // Once the failed session's handle is retired, readiness reports
            // NotRunning rather than resurfacing the start failure.
            assertNull(readinessSignalOf(proxy))
            val readyError = runCatching { proxy.awaitReady() }.exceptionOrNull()
            assertTrue("expected NotRunning, got $readyError", readyError is NativeError.NotRunning)
        }

    @Test
    fun proxyStartAfterEarlyExitUsesFreshHandleAndReadinessSignal() =
        runTest {
            val bindings = FakeRipDpiProxyBindings()
            val proxy = RipDpiProxy(bindings)

            // Session 1 exits before readiness and leaves nothing behind.
            assertEquals(0, proxy.startProxy(prefs(1415)))
            assertNull(readinessSignalOf(proxy))

            // Session 2 runs on a fresh handle with a working readiness signal.
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            bindings.apply {
                createdHandle = 2L
                startedSignal = started
                this.startBlocker = startBlocker
                stopCompletesStartBlocker = true
                telemetryJson = runtimeReadyTelemetry("proxy")
            }

            val startJob = async { proxy.startProxy(prefs(1415)) }
            assertEquals(2L, started.await())

            // The fresh readiness signal completes via the runtime_ready event.
            withContext(Dispatchers.Default) { proxy.awaitReady() }

            proxy.stopProxy()
            assertEquals(0, startJob.await())
            assertEquals(listOf(1L, 2L), bindings.startedHandles.toList())
            assertEquals(listOf(1L, 2L), bindings.destroyedHandles.toList())
            assertEquals(2, bindings.createdPayloads.size)
        }

    @Test
    fun relaySessionExitingBeforeReadyLeavesNoStaleReadinessSignal() =
        runTest {
            // No start blocker: the native relay exits cleanly before ready.
            val relay = RipDpiRelay(FakeRipDpiRelayBindings())

            assertEquals(0, relay.start(relayConfig()))

            assertNull(readinessSignalOf(relay))
            val error = runCatching { relay.awaitReady() }.exceptionOrNull()
            assertTrue("expected NotRunning, got $error", error is NativeError.NotRunning)
        }

    @Test
    fun awaitReadyThrowsNotRunningAfterFailedRelayStartReadiness() =
        runTest {
            val bindings = FakeRipDpiRelayBindings().apply { startFailure = IOException("relay boom") }
            val relay = RipDpiRelay(bindings)

            val startError = runCatching { relay.start(relayConfig()) }.exceptionOrNull()
            assertTrue("expected IOException, got $startError", startError is IOException)

            assertNull(readinessSignalOf(relay))
            val readyError = runCatching { relay.awaitReady() }.exceptionOrNull()
            assertTrue("expected NotRunning, got $readyError", readyError is NativeError.NotRunning)
        }

    @Test
    fun relayStartAfterEarlyExitUsesFreshHandleAndReadinessSignal() =
        runTest {
            val bindings = FakeRipDpiRelayBindings()
            val relay = RipDpiRelay(bindings)

            // Session 1 exits before readiness and leaves nothing behind.
            assertEquals(0, relay.start(relayConfig()))
            assertNull(readinessSignalOf(relay))
            assertEquals(1L, bindings.lastStartedHandle)
            assertEquals(1L, bindings.lastDestroyedHandle)

            // Session 2 runs on a fresh handle with a working readiness signal.
            val started = CompletableDeferred<Long>()
            val startBlocker = CompletableDeferred<Unit>()
            bindings.apply {
                createdHandle = 2L
                startedSignal = started
                this.startBlocker = startBlocker
                telemetryJson = runtimeReadyTelemetry("relay")
            }

            val startJob = async { relay.start(relayConfig()) }
            assertEquals(2L, started.await())

            // The fresh readiness signal completes via the runtime_ready event.
            withContext(Dispatchers.Default) { relay.awaitReady() }

            relay.stop()
            // FakeRipDpiRelayBindings.stop does not release the start blocker;
            // unblock the parked native start so the session coroutine ends.
            startBlocker.complete(Unit)
            assertEquals(0, startJob.await())
            assertEquals(2L, bindings.lastStartedHandle)
            assertEquals(2L, bindings.lastDestroyedHandle)
        }
}
