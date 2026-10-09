@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal class HomeCompositeRunJobs(
    private val scope: CoroutineScope,
    private val lease: DiagnosticsHomeRunLease = DiagnosticsHomeRunLease(),
    private val canAcquire: () -> Boolean = { true },
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val childJobs = ConcurrentHashMap<String, Job>()
    private val teardownRunIds = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = Any()
    private val startupJobs = ConcurrentHashMap<String, Job>()

    suspend fun reserve(runId: String): Boolean {
        if (!lease.acquire(runId, canAcquire)) return false
        currentCoroutineContext()[Job]?.let { startupJobs[runId] = it }
        return true
    }

    suspend fun releaseReservation(runId: String) =
        withContext(NonCancellable) {
            val shouldRelease =
                synchronized(lifecycleLock) {
                    startupJobs.remove(runId)
                    runId !in teardownRunIds && !jobs.containsKey(runId)
                }
            if (shouldRelease) lease.release(runId)
        }

    @Suppress("detekt.TooGenericExceptionCaught")
    suspend fun launch(
        runId: String,
        onFailure: suspend (Throwable) -> Unit,
        block: suspend () -> Unit,
    ): Boolean {
        if (!lease.isOwnedBy(runId) && !reserve(runId)) return false
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onFailure(error)
                } finally {
                    val shouldRelease =
                        synchronized(lifecycleLock) {
                            childJobs.remove(runId)?.cancel()
                            jobs.remove(runId)
                            runId !in teardownRunIds
                        }
                    if (shouldRelease) withContext(NonCancellable) { lease.release(runId) }
                }
            }
        val admitted =
            synchronized(lifecycleLock) {
                if (!lease.isOwnedBy(runId) || jobs.containsKey(runId) || runId in teardownRunIds) {
                    false
                } else {
                    startupJobs.remove(runId)
                    jobs[runId] = job
                    true
                }
            }
        return if (admitted && job.start()) {
            true
        } else {
            job.cancel()
            if (admitted) synchronized(lifecycleLock) { jobs.remove(runId, job) }
            false
        }
    }

    fun trackChild(
        runId: String,
        job: Job,
    ) {
        synchronized(lifecycleLock) {
            if (lease.isOwnedBy(runId) && jobs.containsKey(runId)) {
                childJobs.put(runId, job)?.cancel()
            } else {
                job.cancel()
            }
        }
    }

    suspend fun cancel(
        runId: String,
        teardown: suspend () -> Unit,
    ): Boolean {
        val job =
            synchronized(lifecycleLock) {
                (jobs[runId] ?: startupJobs[runId])?.takeIf { teardownRunIds.add(runId) }
            } ?: return false
        try {
            job.cancelAndJoin()
            teardown()
        } finally {
            synchronized(lifecycleLock) {
                teardownRunIds.remove(runId)
                startupJobs.remove(runId)
            }
            withContext(NonCancellable) { lease.release(runId) }
        }
        return true
    }
}

internal fun DiagnosticsHomeCompositeProgress.outcomeOrThrowIfTerminal(): DiagnosticsHomeCompositeOutcome? =
    outcome
        ?: when (status) {
            DiagnosticsHomeCompositeRunStatus.RUNNING -> null

            DiagnosticsHomeCompositeRunStatus.COMPLETED,
            DiagnosticsHomeCompositeRunStatus.CANCELLED,
            DiagnosticsHomeCompositeRunStatus.FAILED,
            -> throw DiagnosticsHomeRunTerminatedException(status)
        }

internal class HomeCompositeCompletionOrder {
    private var latest: DiagnosticsHomeCompositeOutcome? = null
    private var prior: DiagnosticsHomeCompositeOutcome? = null

    @Synchronized
    fun recordCompleted(outcome: DiagnosticsHomeCompositeOutcome) {
        if (latest?.runId != outcome.runId) prior = latest
        latest = outcome
    }

    @Synchronized
    fun previousBefore(currentRunId: String): DiagnosticsHomeCompositeOutcome? =
        if (latest?.runId == currentRunId) prior else latest
}
