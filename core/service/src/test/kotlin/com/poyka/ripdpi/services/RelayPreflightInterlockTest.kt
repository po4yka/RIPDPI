package com.poyka.ripdpi.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayPreflightInterlockTest {
    @Test
    fun `preflight fails closed while service start owns the admission boundary`() =
        runTest {
            val interlock = RelayPreflightInterlock()
            val serviceEntered = CompletableDeferred<Unit>()
            val releaseService = CompletableDeferred<Unit>()
            val service =
                launch {
                    interlock.runServiceStart {
                        serviceEntered.complete(Unit)
                        releaseService.await()
                    }
                }
            serviceEntered.await()

            val attempt = async { runCatching { interlock.runPreflight { "started" } } }
            runCurrent()
            releaseService.complete(Unit)
            service.join()

            assertEquals(
                listOf(null, RelayPreflightUnavailableException::class),
                listOf(
                    attempt.await().getOrNull(),
                    attempt
                        .await()
                        .exceptionOrNull()
                        ?.javaClass
                        ?.kotlin,
                ),
            )
        }

    @Test
    fun `service start cancels and awaits admitted preflight cleanup before its critical section`() =
        runTest {
            val interlock = RelayPreflightInterlock()
            val cleanupGate = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            launch {
                interlock.runPreflight {
                    events += "preflight-admitted"
                    try {
                        awaitCancellation()
                    } finally {
                        events += "preflight-cleanup-started"
                        withContext(NonCancellable) {
                            cleanupGate.await()
                            events += "preflight-cleanup-finished"
                        }
                    }
                }
            }
            runCurrent()

            launch {
                interlock.cancelAndAwaitPreflight()
                events += "service-critical-section"
            }
            runCurrent()
            cleanupGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "preflight-admitted",
                    "preflight-cleanup-started",
                    "preflight-cleanup-finished",
                    "service-critical-section",
                ),
                events,
            )
        }
}
