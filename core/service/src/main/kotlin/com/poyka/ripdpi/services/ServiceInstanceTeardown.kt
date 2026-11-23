package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Each service type owns one chain of final releases across its Android instances. */
internal class ServiceInstanceTeardown {
    val latest = AtomicReference<Job?>()

    @Volatile var timeoutMillis = 10_000L

    suspend fun awaitPrevious(ownTeardown: Job?) {
        latest.get()?.takeUnless { it === ownTeardown }?.let { predecessor ->
            withTimeoutOrNull(timeoutMillis) { predecessor.join() } ?: throw RuntimeCleanupPendingException()
        }
    }

    // Android serializes onDestroy on the main thread. Chain even an empty instance behind its predecessor.
    fun enqueue(release: suspend () -> RuntimeStopOutcome): Job {
        val predecessor = latest.get()
        return CoroutineScope(Dispatchers.IO)
            .launch {
                predecessor?.join()
                awaitRuntimeRelease(timeoutMillis, release)
            }.also(latest::set)
    }
}

private suspend fun awaitRuntimeRelease(
    timeoutMillis: Long,
    release: suspend () -> RuntimeStopOutcome,
) {
    var reportedPending = false
    while (true) {
        var pendingFailure: Exception? = null
        val outcome =
            try {
                withTimeoutOrNull(timeoutMillis) { release() }
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                pendingFailure = failure
                RuntimeStopOutcome.CleanupPending
            }
        if (outcome == RuntimeStopOutcome.FullyReleased) return
        if (!reportedPending) {
            Logger.e(pendingFailure, tag = LogTags.SERVICE) { "Service destruction retained cleanup ownership" }
            reportedPending = true
        }
        delay(DestructionRetryDelayMillis)
    }
}

private const val DestructionRetryDelayMillis = 250L
