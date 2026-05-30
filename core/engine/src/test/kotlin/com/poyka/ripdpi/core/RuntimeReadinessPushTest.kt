package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the native readiness-push bypass in [awaitRuntimeReady] (ADR 0003):
 * a push that completes the startup signal short-circuits the 50 ms telemetry
 * poll, while polling remains the fallback and the timeout still fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeReadinessPushTest {
    @Test
    fun alreadyPushedSignalReturnsWithoutAnyPoll() =
        runTest {
            val signal = CompletableDeferred<Unit>().apply { complete(Unit) }
            val polls = AtomicInteger(0)

            awaitRuntimeReady(
                startupSignal = signal,
                timeoutMillis = 5_000L,
                pollIntervalMillis = 50L,
                timeoutMessagePrefix = "proxy readiness timed out",
                pollTelemetry = {
                    polls.incrementAndGet()
                    NativeRuntimeSnapshot.idle(source = "proxy")
                },
            )

            assertEquals("a readiness push must short-circuit the telemetry poll", 0, polls.get())
        }

    @Test
    fun pushDuringWaitWakesTheLoopBeforeTheNextPollTick() =
        runTest {
            val signal = CompletableDeferred<Unit>()
            val polls = AtomicInteger(0)

            val waiter =
                launch {
                    awaitRuntimeReady(
                        startupSignal = signal,
                        timeoutMillis = 60_000L,
                        pollIntervalMillis = 50L,
                        timeoutMessagePrefix = "proxy readiness timed out",
                        pollTelemetry = {
                            polls.incrementAndGet()
                            NativeRuntimeSnapshot.idle(source = "proxy")
                        },
                    )
                }

            // Let the loop run one poll and park in withTimeoutOrNull awaiting the
            // push, without advancing virtual time past the 50 ms interval.
            runCurrent()
            val pollsBeforePush = polls.get()
            assertTrue("loop should have polled once before parking", pollsBeforePush >= 1)

            // Native readiness push completes the signal.
            signal.complete(Unit)
            runCurrent()

            assertTrue("push must complete the wait", waiter.isCompleted)
            assertEquals(
                "the loop must wake on the push, not consume another poll tick",
                pollsBeforePush,
                polls.get(),
            )
        }

    @Test
    fun timesOutWithDetailWhenNeitherPushNorPollSignalsReady() =
        runTest {
            val signal = CompletableDeferred<Unit>()

            val error =
                runCatching {
                    awaitRuntimeReady(
                        startupSignal = signal,
                        timeoutMillis = 1_000L,
                        pollIntervalMillis = 50L,
                        timeoutMessagePrefix = "proxy readiness timed out",
                        pollTelemetry = { NativeRuntimeSnapshot.idle(source = "proxy") },
                    )
                }.exceptionOrNull()

            assertTrue("timeout must surface as IllegalStateException", error is IllegalStateException)
            assertTrue(
                "timeout detail must carry the wrapper prefix",
                error?.message?.startsWith("proxy readiness timed out") == true,
            )
        }
}
