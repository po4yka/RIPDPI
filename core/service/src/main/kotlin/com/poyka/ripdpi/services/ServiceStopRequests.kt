package com.poyka.ripdpi.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns a started service's stop decision. A failure stop, and a command's stop that Android refuses because a newer
 * command was delivered, is deferred: a settle runs behind every command queued so far and stops the service only
 * when no runtime session and no pause remains. So a Start queued before or after a failure keeps the service, and a
 * later command that ends with no session still stops it.
 *
 * Commands are delivered on the main thread; stops and settles run on IO coroutines.
 */
internal class ServiceStopRequests(
    private val stopSelfResult: (Int) -> Boolean,
    private val stopSelf: () -> Unit,
    private val isPaused: () -> Boolean,
) {
    // Delivered commands whose service stage has not ended. It is incremented before the volatile write of
    // latestStartId, so a thread that reads a start id also sees that command counted until its stage ends.
    private val commandsInServiceStage = AtomicInteger()

    // Deferred stops are numbered; a settle raises settledStops to the number it read, with a max so overlapping
    // settles never lower it, and a stop deferred after that read stays due.
    private val deferredStops = AtomicLong()
    private val settledStops = AtomicLong()

    @Volatile var latestStartId: Int? = null
        private set

    /**
     * Called on the main thread before the command's coroutine is launched. A command that never ends its stage
     * leaks its count only when onDestroy cancels the service scope, after which no stop is decided.
     */
    fun commandDelivered(startId: Int) {
        commandsInServiceStage.incrementAndGet()
        latestStartId = startId
    }

    /** Called under the entry mutex when the command's service stage ends, before [scheduleSettle]. */
    fun serviceStageEnded() {
        commandsInServiceStage.decrementAndGet()
    }

    /**
     * Stops the service for an explicit command or a start's own failure; returns true when Android refused the stop
     * and the caller must schedule a settle.
     */
    fun request(startId: Int?): Boolean {
        if (startId == null) {
            stopSelf()
            return false
        }
        val deferred = !stopSelfResult(startId)
        if (deferred) deferredStops.incrementAndGet()
        return deferred
    }

    /** Defers a failure stop; the caller must schedule a settle. */
    fun requestAfterQueuedCommands() {
        deferredStops.incrementAndGet()
    }

    /**
     * Returns the start id a settle stops with, or null when no stop is due or a delivered command is still in its
     * service stage (the end of that stage schedules the settle).
     */
    fun settleStartId(): Int? {
        if (deferredStops.get() <= settledStops.get()) return null
        return latestStartId?.takeIf { commandsInServiceStage.get() == 0 }
    }

    /**
     * Schedules a due settle. Call it under the service entry mutex, so [enqueue] puts the settle on the shell command
     * queue behind every command the handled commands queued; without a shell the settle runs at once.
     */
    suspend fun scheduleSettle(
        enqueue: ((suspend () -> Unit) -> Unit)?,
        hasRuntimeSession: suspend () -> Boolean,
    ) {
        val startId = settleStartId() ?: return
        val settle: suspend () -> Unit = { settle(startId, hasRuntimeSession) }
        if (enqueue == null) settle() else enqueue(settle)
    }

    /** Schedules a settle from outside the entry mutex, launched because the caller can hold the lifecycle mutex. */
    fun launchSettle(
        scope: CoroutineScope,
        entryMutex: Mutex,
        schedule: suspend () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) { entryMutex.withLock { schedule() } }
    }

    /**
     * Settles the due stops: a pause or a runtime session (including a Failed one that keeps its barrier) keeps the
     * service; otherwise it stops with [startId]. A command newer than [startId] makes Android refuse, and the end
     * of its service stage schedules the next settle.
     */
    suspend fun settle(
        startId: Int,
        hasRuntimeSession: suspend () -> Boolean,
    ) {
        val deferred = deferredStops.get()
        if (deferred <= settledStops.get()) return
        // Pause first: a resume clears it only inside its start, under the lifecycle mutex hasRuntimeSession waits on.
        if (isPaused() || hasRuntimeSession() || stopSelfResult(startId)) {
            settledStops.accumulateAndGet(deferred) { settled, read -> maxOf(settled, read) }
        }
    }
}
