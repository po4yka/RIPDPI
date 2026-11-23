package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.LogTags
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/** Tracks each attempted attachment before its side effect, including a partially throwing initializer. */
internal class ActiveSessionOwnership {
    private class OwnedResource(
        val name: String,
        val release: suspend () -> Unit,
    )

    // Mutated on IO under the service entry mutex but read from the main thread (onRevoke).
    private val resources = CopyOnWriteArrayList<OwnedResource>()

    @Volatile
    var attached = false
        private set
    val hasOwnership: Boolean get() = attached || resources.isNotEmpty()
    val cleanupPending: Boolean get() = !attached && resources.isNotEmpty()

    fun own(
        name: String,
        release: suspend () -> Unit,
    ) {
        resources += OwnedResource(name, release)
    }

    suspend fun attach(initialize: suspend () -> Unit) {
        if (attached) return
        check(release() == RuntimeStopOutcome.FullyReleased) { "Previous active session cleanup is pending" }
        val failure =
            runCatching {
                initialize()
                attached = true
            }.exceptionOrNull() ?: return
        if (failure !is Exception) throw failure
        val outcome = withContext(NonCancellable) { release() }
        val terminalFailure =
            when {
                failure is kotlinx.coroutines.CancellationException -> {
                    if (outcome == RuntimeStopOutcome.CleanupPending) {
                        failure.addSuppressed(IllegalStateException("Active session cleanup is pending"))
                    }
                    failure
                }

                outcome == RuntimeStopOutcome.CleanupPending -> {
                    ActiveSessionCleanupPendingException(failure)
                }

                else -> {
                    failure
                }
            }
        throw terminalFailure
    }

    suspend fun release(): RuntimeStopOutcome {
        attached = false
        for (resource in resources.toList().asReversed()) {
            try {
                resource.release()
                resources.remove(resource)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                Logger.w(tag = LogTags.SERVICE) {
                    "Active session cleanup pending: resource=${resource.name} error=${failure.javaClass.simpleName}"
                }
            }
        }
        return if (resources.isEmpty()) RuntimeStopOutcome.FullyReleased else RuntimeStopOutcome.CleanupPending
    }
}

internal class ActiveSessionCleanupPendingException(
    cause: Exception,
) : IllegalStateException("Active session cleanup is pending", cause)
