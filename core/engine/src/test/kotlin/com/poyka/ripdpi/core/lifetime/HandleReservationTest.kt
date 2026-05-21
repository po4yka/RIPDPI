package com.poyka.ripdpi.core.lifetime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandleReservationTest {
    @Test
    fun concurrentReservationsRunInParallel() =
        runTest {
            val reservation = HandleReservation()
            val firstStarted = CompletableDeferred<Long>()
            val secondStarted = CompletableDeferred<Long>()
            val firstBlocker = CompletableDeferred<Unit>()
            val secondBlocker = CompletableDeferred<Unit>()

            val first =
                async {
                    reservation.withReservationOrNull({ 7L }) { handle ->
                        firstStarted.complete(handle)
                        firstBlocker.await()
                        "first"
                    }
                }
            val second =
                async {
                    reservation.withReservationOrNull({ 7L }) { handle ->
                        secondStarted.complete(handle)
                        secondBlocker.await()
                        "second"
                    }
                }

            assertEquals(7L, firstStarted.await())
            assertEquals(7L, secondStarted.await())
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)

            firstBlocker.complete(Unit)
            secondBlocker.complete(Unit)

            assertEquals("first", first.await())
            assertEquals("second", second.await())
        }

    @Test
    fun exclusiveDrainsInFlight() =
        runTest {
            val reservation = HandleReservation()
            val started = CompletableDeferred<Long>()
            val blocker = CompletableDeferred<Unit>()
            val reserved =
                async {
                    reservation.withReservationOrNull({ 9L }) { handle ->
                        started.complete(handle)
                        blocker.await()
                    }
                }
            assertEquals(9L, started.await())

            val exclusive = async { reservation.withExclusive { "exclusive" } }
            runCurrent()

            assertFalse(exclusive.isCompleted)

            blocker.complete(Unit)
            reserved.await()

            assertEquals("exclusive", exclusive.await())
        }

    @Test
    fun newReservationsRejectedWhileDraining() =
        runTest {
            val reservation = HandleReservation()
            val started = CompletableDeferred<Long>()
            val blocker = CompletableDeferred<Unit>()
            val reserved =
                async {
                    reservation.withReservationOrNull({ 11L }) { handle ->
                        started.complete(handle)
                        blocker.await()
                    }
                }
            assertEquals(11L, started.await())

            val exclusive = async { reservation.withExclusive { "exclusive" } }
            runCurrent()

            assertNull(
                reservation.withReservationOrNull({ 11L }) {
                    "admitted"
                },
            )

            blocker.complete(Unit)
            reserved.await()
            assertEquals("exclusive", exclusive.await())
        }

    @Test
    fun exclusiveCallsRunOneAtATime() =
        runTest {
            val reservation = HandleReservation()
            val firstStarted = CompletableDeferred<Unit>()
            val firstBlocker = CompletableDeferred<Unit>()
            val first =
                async {
                    reservation.withExclusive {
                        firstStarted.complete(Unit)
                        firstBlocker.await()
                        "first"
                    }
                }
            firstStarted.await()

            val second = async { reservation.withExclusive { "second" } }
            runCurrent()

            assertFalse(second.isCompleted)

            firstBlocker.complete(Unit)

            assertEquals("first", first.await())
            assertEquals("second", second.await())
        }

    @Test
    fun cancelledReservationReleases() =
        runTest {
            val reservation = HandleReservation()
            val started = CompletableDeferred<Long>()
            val blocker = CompletableDeferred<Unit>()
            val reserved =
                async {
                    reservation.withReservationOrNull({ 13L }) { handle ->
                        started.complete(handle)
                        blocker.await()
                    }
                }
            assertEquals(13L, started.await())

            reserved.cancelAndJoin()

            assertEquals("exclusive", reservation.withExclusive { "exclusive" })
        }

    @Test
    fun exclusiveCancelledWhileDrainingDoesNotRunBlock() =
        runTest {
            val reservation = HandleReservation()
            val started = CompletableDeferred<Long>()
            val blocker = CompletableDeferred<Unit>()
            val reserved =
                async {
                    reservation.withReservationOrNull({ 21L }) { handle ->
                        started.complete(handle)
                        blocker.await()
                    }
                }
            assertEquals(21L, started.await())

            val exclusiveRan = CompletableDeferred<Unit>()
            val exclusive = async { reservation.withExclusive { exclusiveRan.complete(Unit) } }
            runCurrent()
            assertFalse(exclusive.isCompleted)
            assertTrue(reservation.isDraining())

            exclusive.cancelAndJoin()

            assertFalse("block must not run when the wait is cancelled", exclusiveRan.isCompleted)
            assertFalse("draining barrier must be restored after cancellation", reservation.isDraining())

            // The in-flight reservation is unaffected and still drains cleanly.
            blocker.complete(Unit)
            reserved.await()

            // A fresh exclusive section works after the abandoned one.
            assertEquals("ok", reservation.withExclusive { "ok" })
        }

    @Test
    fun exclusiveCancelledInsideBlockRestoresState() =
        runTest {
            val reservation = HandleReservation()
            val insideBlock = CompletableDeferred<Unit>()
            val blockGate = CompletableDeferred<Unit>()
            val exclusive =
                async {
                    reservation.withExclusive {
                        insideBlock.complete(Unit)
                        blockGate.await()
                    }
                }
            insideBlock.await()
            assertTrue(reservation.isDraining())

            exclusive.cancelAndJoin()

            assertFalse("draining must be cleared after a cancelled exclusive block", reservation.isDraining())
            assertEquals(7L, reservation.withReservationOrNull({ 7L }) { it })
            assertEquals("ok", reservation.withExclusive { "ok" })
        }

    @Test
    fun drainingHeldForEntireExclusiveBlockAndClearedAfter() =
        runTest {
            val reservation = HandleReservation()
            val insideBlock = CompletableDeferred<Unit>()
            val releaseBlock = CompletableDeferred<Unit>()
            val exclusive =
                async {
                    reservation.withExclusive {
                        insideBlock.complete(Unit)
                        releaseBlock.await()
                    }
                }
            insideBlock.await()

            // Draining stays asserted for the whole block; no reservation slips in.
            assertTrue(reservation.isDraining())
            assertNull(reservation.withReservationOrNull({ 5L }) { "admitted" })

            releaseBlock.complete(Unit)
            exclusive.await()

            // Only after the block fully exits is the barrier cleared.
            assertFalse(reservation.isDraining())
            assertEquals(5L, reservation.withReservationOrNull({ 5L }) { it })
        }

    @Test
    fun cancelledExclusiveDoesNotReleaseDrainBarrierEarly() =
        runTest {
            val reservation = HandleReservation()
            val started = CompletableDeferred<Long>()
            val blocker = CompletableDeferred<Unit>()
            val reserved =
                async {
                    reservation.withReservationOrNull({ 41L }) { handle ->
                        started.complete(handle)
                        blocker.await()
                    }
                }
            assertEquals(41L, started.await())

            // A first exclusive starts waiting for the reservation, then is cancelled.
            val firstExclusive = async { reservation.withExclusive { "first" } }
            runCurrent()
            firstExclusive.cancelAndJoin()

            // A second exclusive must still wait for the *real* reservation to drain;
            // the cancelled first one must not have completed the drain barrier early.
            val secondBlockRan = CompletableDeferred<Unit>()
            val secondExclusive =
                async {
                    reservation.withExclusive {
                        secondBlockRan.complete(Unit)
                        "second"
                    }
                }
            runCurrent()
            assertFalse("drain barrier released while a reservation is still active", secondBlockRan.isCompleted)

            blocker.complete(Unit)
            reserved.await()

            assertEquals("second", secondExclusive.await())
            assertTrue(secondBlockRan.isCompleted)
        }

    @Test
    fun exclusiveNonCancellableReturnsBlockValue() =
        runTest {
            val reservation = HandleReservation()
            assertEquals("done", reservation.withExclusiveNonCancellable { "done" })
        }

    @Test
    fun exclusiveNonCancellableCompletesBlockWhenCancelledWhileDraining() =
        runTest {
            val reservation = HandleReservation()
            val started = CompletableDeferred<Long>()
            val blocker = CompletableDeferred<Unit>()
            val reserved =
                async {
                    reservation.withReservationOrNull({ 31L }) { handle ->
                        started.complete(handle)
                        blocker.await()
                    }
                }
            assertEquals(31L, started.await())

            val cleanupRan = CompletableDeferred<Unit>()
            val cleanup = launch { reservation.withExclusiveNonCancellable { cleanupRan.complete(Unit) } }
            runCurrent()
            assertFalse(cleanup.isCompleted)
            assertTrue(reservation.isDraining())

            // Caller is cancelled mid-drain: the cleanup section must not be abandoned.
            cleanup.cancel()
            runCurrent()
            assertFalse(cleanup.isCompleted)
            assertFalse(cleanupRan.isCompleted)

            blocker.complete(Unit)
            reserved.await()
            cleanup.join()

            assertTrue("lifecycle cleanup must run despite caller cancellation", cleanupRan.isCompleted)
            assertFalse(reservation.isDraining())
        }

    @Test
    fun exclusiveNonCancellableIgnoresCancellationInsideBlock() =
        runTest {
            val reservation = HandleReservation()
            val insideBlock = CompletableDeferred<Unit>()
            val blockGate = CompletableDeferred<Unit>()
            val blockCompleted = CompletableDeferred<Unit>()
            val cleanup =
                launch {
                    reservation.withExclusiveNonCancellable {
                        insideBlock.complete(Unit)
                        blockGate.await()
                        blockCompleted.complete(Unit)
                    }
                }
            insideBlock.await()

            cleanup.cancel()
            runCurrent()
            assertFalse("a cancelled caller must not abort the cleanup block", blockCompleted.isCompleted)

            blockGate.complete(Unit)
            cleanup.join()

            assertTrue(blockCompleted.isCompleted)
            assertFalse(reservation.isDraining())
        }
}
