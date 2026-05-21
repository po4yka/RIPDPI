package com.poyka.ripdpi.core.lifetime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates read-style native handle use with exclusive lifecycle mutations.
 *
 * The reservation owns only admission state; callers still publish, clear, and
 * destroy native handles.
 *
 * ## Cancellation
 * - [withReservationOrNull] always releases its reservation, even when the
 *   caller is cancelled while `block` runs: the release runs under
 *   [NonCancellable] so the drain counter can never leak. A leaked counter
 *   would wedge every future [withExclusive] drain.
 * - [withExclusive] may be cancelled while it waits for in-flight reservations
 *   to drain; if so the wait is abandoned, `block` never runs, and the
 *   `draining` barrier is restored. Use it for normal exclusive sections where
 *   giving up on the wait is acceptable.
 * - [withExclusiveNonCancellable] runs the drain wait and `block` under
 *   [NonCancellable]: caller cancellation never interrupts it. Use it for
 *   lifecycle stop/destroy sections that MUST finish so the native handle is
 *   never left half-retired.
 *
 * In every case the `draining` flag is cleared only after the exclusive
 * `block` has fully returned (or been abandoned before it started), never
 * while it is still mid-flight or while reservations are still in flight for
 * it to drain.
 */
class HandleReservation {
    private val mutex = Mutex()
    private val exclusiveMutex = Mutex()

    @Volatile private var draining = false
    private var reservations = 0
    private var idle = CompletableDeferred<Unit>().apply { complete(Unit) }

    suspend fun <T> withReservationOrNull(
        currentHandle: () -> Long,
        block: suspend (Long) -> T,
    ): T? {
        val handle =
            mutex.withLock {
                val currentHandle = currentHandle().takeUnless { draining || it == 0L }
                if (currentHandle != null && reservations == 0) {
                    idle = CompletableDeferred()
                }
                if (currentHandle != null) {
                    reservations += 1
                }
                currentHandle
            }
        return handle?.let { reservedHandle ->
            try {
                block(reservedHandle)
            } finally {
                // Releasing must never be skipped by cancellation: a leaked
                // reservation count would block every future exclusive drain.
                withContext(NonCancellable) {
                    releaseReservation()
                }
            }
        }
    }

    /**
     * Runs [block] as an exclusive section once all in-flight reservations have
     * drained. If the caller is cancelled while waiting to drain, the wait is
     * abandoned and [block] never runs. See the class-level cancellation notes.
     */
    suspend fun <T> withExclusive(block: suspend () -> T): T =
        exclusiveMutex.withLock {
            runExclusive(block)
        }

    /**
     * Like [withExclusive], but the drain wait and [block] run under
     * [NonCancellable] so caller cancellation cannot skip a lifecycle
     * stop/destroy section partway. See the class-level cancellation notes.
     */
    suspend fun <T> withExclusiveNonCancellable(block: suspend () -> T): T =
        withContext(NonCancellable) {
            exclusiveMutex.withLock {
                runExclusive(block)
            }
        }

    fun isDraining(): Boolean = draining

    private suspend fun <T> runExclusive(block: suspend () -> T): T {
        val waitForIdle =
            mutex.withLock {
                draining = true
                idle.takeIf { reservations > 0 }
            }
        try {
            waitForIdle?.await()
            return block()
        } finally {
            // Restoring the barrier must never be skipped by cancellation:
            // a stuck `draining` flag would reject every future reservation.
            withContext(NonCancellable) {
                mutex.withLock {
                    draining = false
                    if (reservations == 0 && !idle.isCompleted) {
                        idle.complete(Unit)
                    }
                }
            }
        }
    }

    private suspend fun releaseReservation() {
        mutex.withLock {
            reservations -= 1
            check(reservations >= 0) { "Handle reservation counter underflow" }
            if (reservations == 0) {
                idle.complete(Unit)
            }
        }
    }
}
