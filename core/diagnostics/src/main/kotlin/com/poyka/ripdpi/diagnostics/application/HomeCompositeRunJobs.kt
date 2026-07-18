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

    @Suppress("detekt.TooGenericExceptionCaught")
    fun launch(
        runId: String,
        onFailure: suspend (Throwable) -> Unit,
        block: suspend () -> Unit,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onFailure(error)
                } finally {
                    childJobs.remove(runId)?.cancel()
                    jobs.remove(runId)
                }
            }
        jobs[runId] = job
        job.start()
    }

    fun trackChild(
        runId: String,
        job: Job,
    ) {
        childJobs.put(runId, job)?.cancel()
    }

    suspend fun cancel(
        runId: String,
        beforeCancel: suspend () -> Unit,
    ): Boolean {
        val job = jobs[runId] ?: return false
        beforeCancel()
        job.cancelAndJoin()
        return true
    }
}
