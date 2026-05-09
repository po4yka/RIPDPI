package com.poyka.ripdpi.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ProtectSocketSessionDispatcher(
    handlerConcurrency: Int,
    maxPendingSessions: Int,
    private val joinTimeoutMs: Long,
) {
    private val closed = AtomicBoolean(false)
    private val permits: Semaphore
    private val activeTasks = ConcurrentHashMap.newKeySet<ProtectSocketHandlerTask>()
    private val executor: ThreadPoolExecutor

    init {
        require(handlerConcurrency > 0) { "handlerConcurrency must be > 0" }
        require(maxPendingSessions >= 0) { "maxPendingSessions must be >= 0" }
        require(joinTimeoutMs >= 0L) { "joinTimeoutMs must be >= 0" }

        permits = Semaphore(handlerConcurrency + maxPendingSessions)
        executor =
            ThreadPoolExecutor(
                handlerConcurrency,
                handlerConcurrency,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                ProtectSocketThreadFactory(),
            )
    }

    fun submit(
        session: ProtectSocketClientSession,
        handler: (ProtectSocketClientSession) -> Unit,
    ): Boolean {
        if (!tryAcquirePermit()) {
            rejectSession(session)
            return false
        }

        val task = ProtectSocketHandlerTask(session, permits, handler, activeTasks)
        activeTasks += task
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            activeTasks -= task
            permits.release()
            rejectSession(session)
            false
        }
    }

    private fun tryAcquirePermit(): Boolean {
        if (closed.get() || !permits.tryAcquire()) return false
        val closedAfterAcquire = closed.get()
        if (closedAfterAcquire) permits.release()
        return !closedAfterAcquire
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(joinTimeoutMs)
        closeActiveSessions()
        executor.shutdown()
        if (awaitTerminationBefore(deadlineNanos)) return

        executor
            .shutdownNow()
            .filterIsInstance<ProtectSocketHandlerTask>()
            .forEach(ProtectSocketHandlerTask::closeSessionQuietly)
        closeActiveSessions()
        awaitTerminationBefore(deadlineNanos)
    }

    private fun awaitTerminationBefore(deadlineNanos: Long): Boolean {
        var result = executor.isTerminated
        while (!result) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                result = executor.isTerminated
                break
            }
            result =
                try {
                    executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    executor.isTerminated
                }
        }
        return result
    }

    private fun closeActiveSessions() {
        activeTasks.forEach(ProtectSocketHandlerTask::closeSessionQuietly)
    }

    private fun rejectSession(session: ProtectSocketClientSession) {
        runCatching {
            session.use {
                it.writeAck(success = false)
            }
        }
    }
}

private class ProtectSocketHandlerTask(
    private val session: ProtectSocketClientSession,
    private val permits: Semaphore,
    private val handler: (ProtectSocketClientSession) -> Unit,
    private val activeTasks: MutableSet<ProtectSocketHandlerTask>,
) : Runnable {
    override fun run() {
        try {
            handler(session)
        } finally {
            permits.release()
            activeTasks.remove(this)
        }
    }

    fun closeSessionQuietly() {
        runCatching { session.close() }
    }
}

private class ProtectSocketThreadFactory : ThreadFactory {
    private val index = AtomicInteger(0)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "vpn-protect-handler-${index.incrementAndGet()}").apply {
            isDaemon = true
        }
}
