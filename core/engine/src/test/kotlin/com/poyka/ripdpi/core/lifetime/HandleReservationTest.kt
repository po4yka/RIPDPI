package com.poyka.ripdpi.core.lifetime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
