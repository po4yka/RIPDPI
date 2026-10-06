package com.poyka.ripdpi.services

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Tracks each attempted attachment before its side effect, including a partially throwing initializer. */
internal class ActiveSessionOwnership {
    private class OwnedResource(
        val release: suspend () -> Unit,
    )

    private val resources = mutableListOf<OwnedResource>()
    var attached = false
        private set
    val hasOwnership: Boolean get() = attached || resources.isNotEmpty()
    val cleanupPending: Boolean get() = !attached && resources.isNotEmpty()

    fun own(release: suspend () -> Unit) {
        resources += OwnedResource(release)
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
            } catch (_: Exception) {
                // The remaining ownership is retained for the next explicit checked cleanup attempt.
            }
        }
        return if (resources.isEmpty()) RuntimeStopOutcome.FullyReleased else RuntimeStopOutcome.CleanupPending
    }
}

internal class ActiveSessionCleanupPendingException(
    cause: Exception,
) : IllegalStateException("Active session cleanup is pending", cause)
