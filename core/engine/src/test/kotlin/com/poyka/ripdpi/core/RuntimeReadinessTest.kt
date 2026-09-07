package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behaviour tests for [awaitRuntimeReady], the readiness-polling loop shared by
 * [RipDpiProxy] and [RipDpiRelay]. Each case drives the helper directly with a
 * supplied telemetry probe so the loop mechanics are exercised without a native
 * binding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeReadinessTest {
    private fun runtimeReadySnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "test",
            state = "running",
            health = "healthy",
            nativeEvents =
                listOf(
                    NativeRuntimeEvent(
                        source = "test",
                        level = "info",
                        message = "listener started",
                        createdAt = 1L,
                        kind = "runtime_ready",
                    ),
                ),
        )

    private fun pendingSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            source = "test",
            state = "running",
            health = "degraded",
            nativeEvents =
                listOf(
                    NativeRuntimeEvent(
                        source = "test",
                        level = "info",
                        message = "transport handshake",
                        createdAt = 1L,
                        kind = "progress",
                    ),
                ),
        )

    @Test
    fun alreadyCompletedSignalReturnsWithoutPolling() =
        runTest {
            val signal = CompletableDeferred<Unit>().apply { complete(Unit) }
            val polls = AtomicInteger(0)

            awaitRuntimeReady(
                startupSignal = signal,
                timeoutMillis = 5_000L,
                pollIntervalMillis = 25L,
                timeoutMessagePrefix = "test readiness timed out",
                pollTelemetry = {
                    polls.incrementAndGet()
                    pendingSnapshot()
                },
            )

            // A signal completed before the first loop iteration short-circuits
            // the wait: telemetry is never polled.
            assertEquals(0, polls.get())
        }

    @Test
    fun runtimeReadyEventCompletesSignal() =
        runTest {
            val signal = CompletableDeferred<Unit>()

            awaitRuntimeReady(
                startupSignal = signal,
                timeoutMillis = 5_000L,
                pollIntervalMillis = 25L,
                timeoutMessagePrefix = "test readiness timed out",
                pollTelemetry = { runtimeReadySnapshot() },
            )

            // A telemetry snapshot carrying the runtime_ready event completes
            // the startup signal and ends the wait normally.
            assertTrue(signal.isCompleted)
            assertFalse(signal.isCancelled)
        }

    @Test
    fun exceptionalStartupSignalPropagates() =
        runTest {
            val signal = CompletableDeferred<Unit>()
            signal.completeExceptionally(IOException("startup boom"))

            val error =
                runCatching {
                    awaitRuntimeReady(
                        startupSignal = signal,
                        timeoutMillis = 5_000L,
                        pollIntervalMillis = 25L,
                        timeoutMessagePrefix = "test readiness timed out",
                        pollTelemetry = { pendingSnapshot() },
                    )
                }.exceptionOrNull()

            // An exceptionally completed signal surfaces its failure verbatim.
            assertTrue("expected IOException, got $error", error is IOException)
        }

    @Test
    fun outerTimeoutPreservesCancellationWithoutReportingStartupTimeout() =
        runTest {
            var timeoutReported = false
            val error =
                runCatching {
                    withTimeout(20L) {
                        awaitRuntimeReady(
                            startupSignal = CompletableDeferred(),
                            timeoutMillis = 1_000L,
                            pollIntervalMillis = 50L,
                            timeoutMessagePrefix = "test readiness timed out",
                            pollTelemetry = { pendingSnapshot() },
                            onTimeout = { timeoutReported = true },
                        )
                    }
                }.exceptionOrNull()

            assertTrue("expected cancellation, got $error", error is TimeoutCancellationException)
            assertFalse(timeoutReported)
        }

    @Test
    fun telemetryTimeoutPreservesCancellationWithoutReportingStartupTimeout() =
        runTest {
            var timeoutReported = false
            val error =
                runCatching {
                    awaitRuntimeReady(
                        startupSignal = CompletableDeferred(),
                        timeoutMillis = 1_000L,
                        pollIntervalMillis = 50L,
                        timeoutMessagePrefix = "test readiness timed out",
                        pollTelemetry = {
                            withTimeout(20L) {
                                delay(100L)
                                pendingSnapshot()
                            }
                        },
                        onTimeout = { timeoutReported = true },
                    )
                }.exceptionOrNull()

            assertTrue("expected cancellation, got $error", error is TimeoutCancellationException)
            assertFalse(timeoutReported)
        }

    @Test
    fun timeoutErrorReportsLastStateAndEvent() =
        runTest {
            val signal = CompletableDeferred<Unit>()
            var timeoutDetails: String? = null

            val error =
                runCatching {
                    awaitRuntimeReady(
                        startupSignal = signal,
                        timeoutMillis = 200L,
                        pollIntervalMillis = 25L,
                        timeoutMessagePrefix = "test readiness timed out",
                        pollTelemetry = { pendingSnapshot() },
                        onTimeout = { timeoutDetails = it },
                    )
                }.exceptionOrNull()

            // The timeout carries the last observed telemetry state and event,
            // and onTimeout sees the same detail string that is thrown.
            assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
            val message = error?.message.orEmpty()
            assertEquals("test readiness timed out state=running lastEvent=transport handshake", message)
            assertEquals(message, timeoutDetails)
        }
}
