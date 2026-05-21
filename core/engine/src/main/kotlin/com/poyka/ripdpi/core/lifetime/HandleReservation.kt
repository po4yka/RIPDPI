package com.poyka.ripdpi.core.lifetime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates read-style native handle use with exclusive lifecycle mutations.
 *
 * The reservation owns only admission state; callers still publish, clear, and destroy native handles.
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
                releaseReservation()
            }
        }
    }

    suspend fun <T> withExclusive(block: suspend () -> T): T =
        exclusiveMutex.withLock {
            runExclusive(block)
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
            mutex.withLock {
                draining = false
                if (reservations == 0 && !idle.isCompleted) {
                    idle.complete(Unit)
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
