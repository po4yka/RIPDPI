@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class HomeCompositeRunJobs(
    private val scope: CoroutineScope,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val childJobs = ConcurrentHashMap<String, Job>()
    private val teardownRunIds = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = Any()
    private var activeRunId: String? = null

    @Suppress("detekt.TooGenericExceptionCaught")
    fun launch(
        runId: String,
        onFailure: suspend (Throwable) -> Unit,
        block: suspend () -> Unit,
    ): Boolean {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onFailure(error)
                } finally {
                    synchronized(lifecycleLock) {
                        childJobs.remove(runId)?.cancel()
                        jobs.remove(runId)
                        if (runId !in teardownRunIds && activeRunId == runId) activeRunId = null
                    }
                }
            }
        val admitted =
            synchronized(lifecycleLock) {
                if (activeRunId != null) {
                    false
                } else {
                    activeRunId = runId
                    jobs[runId] = job
                    true
                }
            }
        if (!admitted) {
            job.cancel()
            return false
        }
        job.start()
        return true
    }

    fun trackChild(
        runId: String,
        job: Job,
    ) {
        synchronized(lifecycleLock) {
            if (activeRunId == runId && jobs.containsKey(runId)) {
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
                jobs[runId]?.takeIf { teardownRunIds.add(runId) }
            } ?: return false
        try {
            job.cancelAndJoin()
            teardown()
        } finally {
            synchronized(lifecycleLock) {
                teardownRunIds.remove(runId)
                if (activeRunId == runId) activeRunId = null
            }
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

internal fun Map<String, DiagnosticsHomeCompositeOutcome>.mostRecentCompletedRunBefore(
    currentRunId: String,
): DiagnosticsHomeCompositeOutcome? =
    entries
        .asSequence()
        .filter { (key, _) -> key != currentRunId }
        .map { (_, value) -> value }
        .maxByOrNull { it.bundleSessionIds.size }
