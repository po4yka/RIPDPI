package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSessionOwnershipTest {
    @Test
    fun `successful attachment is idempotent and releases in reverse ownership order`() =
        runTest {
            val ownership = ActiveSessionOwnership()
            val events = mutableListOf<String>()
            ownership.attach {
                ownership.own { events += "release binder" }
                ownership.own { events += "release listener" }
                events += "attached"
            }
            ownership.attach { error("already attached graph must not be created twice") }
            assertTrue(ownership.attached)
            assertTrue(ownership.hasOwnership)
            assertFalse(ownership.cleanupPending)

            assertEquals(RuntimeStopOutcome.FullyReleased, ownership.release())
            assertEquals(listOf("attached", "release listener", "release binder"), events)
            assertFalse(ownership.attached)
            assertFalse(ownership.hasOwnership)
            assertFalse(ownership.cleanupPending)
            assertEquals(RuntimeStopOutcome.FullyReleased, ownership.release())
            assertEquals(3, events.size)
        }

    @Test
    fun `partially throwing initializer releases its attempted stage and earlier resources`() =
        runTest {
            val ownership = ActiveSessionOwnership()
            val events = mutableListOf<String>()
            val original = IllegalStateException("listener initialization failed")
            val failure =
                runCatching {
                    ownership.attach {
                        ownership.own { events += "release binder" }
                        events += "start binder"
                        ownership.own { events += "release partial listener" }
                        events += "start listener"
                        throw original
                    }
                }.exceptionOrNull()

            assertSame(original, failure)
            assertEquals(listOf("start binder", "start listener", "release partial listener", "release binder"), events)
            assertFalse(ownership.attached)
            assertFalse(ownership.hasOwnership)
            assertFalse(ownership.cleanupPending)
        }

    @Test
    fun `failed rollback retains only unreleased ownership and retry completes it`() =
        runTest {
            val ownership = ActiveSessionOwnership()
            val events = mutableListOf<String>()
            var releaseFails = true
            val original = IllegalStateException("startup failed")
            val failure =
                runCatching {
                    ownership.attach {
                        ownership.own { events += "release binder" }
                        ownership.own {
                            events += "release listener"
                            check(!releaseFails) { "listener cleanup failed" }
                        }
                        throw original
                    }
                }.exceptionOrNull()

            assertTrue(failure is ActiveSessionCleanupPendingException)
            assertSame(original, failure?.cause)
            assertEquals(listOf("release listener", "release binder"), events)
            assertFalse(ownership.attached)
            assertTrue(ownership.hasOwnership)
            assertTrue(ownership.cleanupPending)
            releaseFails = false
            assertEquals(RuntimeStopOutcome.FullyReleased, ownership.release())
            assertEquals(listOf("release listener", "release binder", "release listener"), events)
            assertFalse(ownership.hasOwnership)
            assertFalse(ownership.cleanupPending)
        }

    @Test
    fun `pending cleanup blocks a second initialization without losing its owner`() =
        runTest {
            val ownership = ActiveSessionOwnership()
            var releaseFails = true
            var secondInitializations = 0
            runCatching {
                ownership.attach {
                    ownership.own { check(!releaseFails) { "cleanup still pending" } }
                    error("first initialization failed")
                }
            }
            assertTrue(ownership.cleanupPending)

            val blocked = runCatching { ownership.attach { secondInitializations += 1 } }.exceptionOrNull()
            assertTrue(blocked is IllegalStateException)
            assertEquals(0, secondInitializations)
            assertTrue(ownership.hasOwnership)
            assertTrue(ownership.cleanupPending)
            releaseFails = false
            assertEquals(RuntimeStopOutcome.FullyReleased, ownership.release())
            ownership.attach { secondInitializations += 1 }
            assertEquals(1, secondInitializations)
            assertTrue(ownership.attached)
            assertEquals(RuntimeStopOutcome.FullyReleased, ownership.release())
        }

    @Test
    fun `cancelled initialization completes suspending cleanup and preserves cancellation`() =
        runTest {
            val ownership = ActiveSessionOwnership()
            val entered = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val operation =
                async {
                    ownership.attach {
                        ownership.own { events += "release binder" }
                        ownership.own {
                            events += "cleanup begins"
                            delay(1)
                            events += "cleanup completes"
                        }
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            operation.cancel()
            operation.join()

            assertTrue(operation.isCancelled)
            assertEquals(listOf("cleanup begins", "cleanup completes", "release binder"), events)
            assertFalse(ownership.attached)
            assertFalse(ownership.hasOwnership)
            assertFalse(ownership.cleanupPending)
        }

    @Test
    fun `cancelled initializer keeps cancellation when suspending cleanup fails and can retry`() =
        runTest {
            val ownership = ActiveSessionOwnership()
            val cancelled = CancellationException("cancelled initialization")
            val events = mutableListOf<String>()
            var releaseFails = true
            val failure =
                runCatching {
                    ownership.attach {
                        ownership.own {
                            events += "cleanup begins"
                            delay(1)
                            check(!releaseFails) { "cleanup failed" }
                            events += "cleanup completes"
                        }
                        throw cancelled
                    }
                }.exceptionOrNull()

            assertSame(cancelled, failure)
            assertTrue(failure is CancellationException)
            assertEquals(listOf("cleanup begins"), events)
            assertFalse(ownership.attached)
            assertTrue(ownership.hasOwnership)
            assertTrue(ownership.cleanupPending)
            releaseFails = false
            assertEquals(RuntimeStopOutcome.FullyReleased, ownership.release())
            assertEquals(listOf("cleanup begins", "cleanup begins", "cleanup completes"), events)
            assertFalse(ownership.hasOwnership)
            assertFalse(ownership.cleanupPending)
        }
}
