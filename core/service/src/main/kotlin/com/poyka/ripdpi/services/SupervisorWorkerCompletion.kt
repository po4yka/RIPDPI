package com.poyka.ripdpi.services

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/** A supervisor retains worker ownership whenever teardown misses its deadline. */
internal suspend fun awaitSupervisorWorkerCompletion(
    job: Job?,
    timeoutMillis: Long,
) {
    val stopped =
        withTimeoutOrNull(timeoutMillis) {
            job?.join()
            true
        } == true
    if (!stopped && job?.isCompleted != true) throw RuntimeCleanupPendingException()
}
