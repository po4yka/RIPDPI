package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeError
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Locking-model tests for [RipDpiRelay] after the migration from a single
 * `Mutex` to [com.poyka.ripdpi.core.lifetime.HandleReservation]: telemetry
 * polls overlap, [RipDpiRelay.stop] drains in-flight telemetry before retiring
 * the handle, and lifecycle ordering (`AlreadyRunning`, fresh handle on
 * restart) is preserved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RipDpiRelayLockingTest {
    private val json = Json

    private fun runningTelemetryJson(): String =
        json.encodeToString(
            NativeRuntimeSnapshot.serializer(),
            NativeRuntimeSnapshot(source = "relay", state = "running", health = "healthy"),
        )

    @Test
    fun telemetryReservationsOverlap() =
        runTest {
            val startBlocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val firstEnteredSignal = CompletableDeferred<Long>()
            val secondEnteredSignal = CompletableDeferred<Long>()
            val bindings =
                LockingRelayBindings().apply {
                    this.startBlocker = startBlocker
                    startedSignal = started
                    stopReleasesStart = true
                    this.telemetryBlocker = telemetryBlocker
                    telemetryJson = runningTelemetryJson()
                    telemetryEnteredSignals += firstEnteredSignal
                    telemetryEnteredSignals += secondEnteredSignal
                }
            val relay = RipDpiRelay(bindings)

            val startJob = async { relay.start(relayConfig()) }
            assertEquals(1L, started.await())

            val firstTelemetry = async { relay.pollTelemetry() }
            val secondTelemetry = async { relay.pollTelemetry() }

            // Both telemetry JNI calls enter while the other is still blocked:
            // the old single-mutex model admitted only one at a time.
            assertEquals(1L, firstEnteredSignal.await())
            assertEquals(1L, secondEnteredSignal.await())

            telemetryBlocker.complete(Unit)
            assertEquals("running", firstTelemetry.await().state)
            assertEquals("running", secondTelemetry.await().state)

            relay.stop()
            assertEquals(0, startJob.await())
        }

    @Test
    fun stopWaitsForInFlightTelemetryBeforeDestroy() =
        runTest {
            val startBlocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val enteredSignal = CompletableDeferred<Long>()
            val bindings =
                LockingRelayBindings().apply {
                    this.startBlocker = startBlocker
                    startedSignal = started
                    stopReleasesStart = true
                    this.telemetryBlocker = telemetryBlocker
                    telemetryJson = runningTelemetryJson()
                    telemetryEnteredSignals += enteredSignal
                }
            val relay = RipDpiRelay(bindings)

            val startJob = async { relay.start(relayConfig()) }
            assertEquals(1L, started.await())

            val telemetryJob = async { relay.pollTelemetry() }
            assertEquals(1L, enteredSignal.await())

            val stopJob = async { relay.stop() }
            runCurrent()

            // stop drains the in-flight telemetry reservation: it must not have
            // completed, and the handle must not have been destroyed yet.
            assertFalse(stopJob.isCompleted)
            assertTrue(bindings.destroyedHandles.isEmpty())

            telemetryBlocker.complete(Unit)

            assertEquals("running", telemetryJob.await().state)
            stopJob.await()
            assertEquals(listOf(1L), bindings.destroyedHandles.toList())
            assertEquals(0, startJob.await())
        }

    @Test
    fun newTelemetryRejectedWhileStopDraining() =
        runTest {
            val startBlocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val telemetryBlocker = CompletableDeferred<Unit>()
            val enteredSignal = CompletableDeferred<Long>()
            val bindings =
                LockingRelayBindings().apply {
                    this.startBlocker = startBlocker
                    startedSignal = started
                    stopReleasesStart = true
                    this.telemetryBlocker = telemetryBlocker
                    telemetryJson = runningTelemetryJson()
                    telemetryEnteredSignals += enteredSignal
                }
            val relay = RipDpiRelay(bindings)

            val startJob = async { relay.start(relayConfig()) }
            assertEquals(1L, started.await())

            // Telemetry A reserves the handle and blocks inside the JNI call.
            val telemetryA = async { relay.pollTelemetry() }
            assertEquals(1L, enteredSignal.await())

            // stop begins draining and waits for telemetry A.
            val stopJob = async { relay.stop() }
            runCurrent()
            assertFalse(stopJob.isCompleted)

            // A fresh telemetry call is rejected while draining: it yields the
            // idle snapshot and never reaches the JNI binding.
            val rejected = relay.pollTelemetry()
            assertEquals(NativeRuntimeSnapshot.idle(source = "relay"), rejected)
            assertEquals(1, bindings.telemetryHandles.size)

            telemetryBlocker.complete(Unit)
            assertEquals("running", telemetryA.await().state)
            stopJob.await()
            assertEquals(listOf(1L), bindings.destroyedHandles.toList())
            assertEquals(0, startJob.await())
        }

    @Test
    fun secondStartWhileLiveThrowsAlreadyRunning() =
        runTest {
            val startBlocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val bindings =
                LockingRelayBindings().apply {
                    this.startBlocker = startBlocker
                    startedSignal = started
                    stopReleasesStart = true
                }
            val relay = RipDpiRelay(bindings)

            val startJob = async { relay.start(relayConfig()) }
            assertEquals(1L, started.await())

            val error = runCatching { relay.start(relayConfig()) }.exceptionOrNull()
            assertTrue("expected AlreadyRunning, got $error", error is NativeError.AlreadyRunning)

            relay.stop()
            assertEquals(0, startJob.await())
            // The rejected second start never created or started a handle.
            assertEquals(listOf(1L), bindings.startedHandles.toList())
        }

    @Test
    fun startAfterStopAcquiresFreshHandle() =
        runTest {
            val startBlocker = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Long>()
            val bindings =
                LockingRelayBindings().apply {
                    this.startBlocker = startBlocker
                    startedSignal = started
                    stopReleasesStart = true
                }
            val relay = RipDpiRelay(bindings)

            // Session 1 runs on handle 1 until stop drains and retires it.
            val firstSession = async { relay.start(relayConfig()) }
            assertEquals(1L, started.await())
            relay.stop()
            assertEquals(0, firstSession.await())

            // Session 2 must create and start a fresh handle. The start blocker
            // is already released, so this session self-completes.
            assertEquals(0, relay.start(relayConfig()))

            assertEquals(listOf(1L, 2L), bindings.createdHandles.toList())
            assertEquals(listOf(1L, 2L), bindings.startedHandles.toList())
            assertEquals(listOf(1L, 2L), bindings.destroyedHandles.toList())
        }

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
        )

    /**
     * Relay binding fake whose `start` and `pollTelemetry` block on test-owned
     * latches. `pollTelemetry` completes the next queued [telemetryEnteredSignals]
     * entry so the test can observe overlapping reservations; `create` hands out
     * a fresh incrementing handle so restart can be asserted.
     */
    private class LockingRelayBindings : RipDpiRelayBindings {
        private val handleCounter = AtomicLong(0L)
        val createdHandles = CopyOnWriteArrayList<Long>()
        val startedHandles = CopyOnWriteArrayList<Long>()
        val stoppedHandles = CopyOnWriteArrayList<Long>()
        val destroyedHandles = CopyOnWriteArrayList<Long>()
        val telemetryHandles = CopyOnWriteArrayList<Long>()
        val telemetryEnteredSignals = ConcurrentLinkedQueue<CompletableDeferred<Long>>()

        @Volatile var startBlocker: CompletableDeferred<Unit>? = null

        @Volatile var startedSignal: CompletableDeferred<Long>? = null

        @Volatile var stopReleasesStart: Boolean = false

        @Volatile var telemetryBlocker: CompletableDeferred<Unit>? = null

        @Volatile var telemetryJson: String? = null

        override fun create(configJson: String): Long {
            val newHandle = handleCounter.incrementAndGet()
            createdHandles += newHandle
            return newHandle
        }

        override fun start(handle: Long): Int {
            startedHandles += handle
            startedSignal?.complete(handle)
            startBlocker?.let { blocker -> blockUntil(blocker, "start") }
            return 0
        }

        override fun stop(handle: Long) {
            stoppedHandles += handle
            if (stopReleasesStart) {
                startBlocker?.complete(Unit)
            }
        }

        override fun pollTelemetry(handle: Long): String? {
            telemetryHandles += handle
            telemetryEnteredSignals.poll()?.complete(handle)
            telemetryBlocker?.let { blocker -> blockUntil(blocker, "pollTelemetry") }
            return telemetryJson
        }

        override fun destroy(handle: Long) {
            destroyedHandles += handle
        }

        private fun blockUntil(
            blocker: CompletableDeferred<Unit>,
            label: String,
        ) {
            try {
                runBlocking {
                    withTimeout(60_000L) {
                        blocker.await()
                    }
                }
            } catch (error: TimeoutCancellationException) {
                throw AssertionError("LockingRelayBindings.$label blocked for more than 60000ms", error)
            }
        }
    }
}
