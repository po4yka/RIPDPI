package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException

internal sealed class SupervisorExitCause {
    data object ExpectedStop : SupervisorExitCause()

    data class Crash(
        val code: Int,
    ) : SupervisorExitCause()

    data class StartupFailure(
        val throwable: Exception,
    ) : SupervisorExitCause()

    data object Cancellation : SupervisorExitCause()
}

internal class SupervisorStartupFailureException(
    val exitCause: SupervisorExitCause.StartupFailure,
) : Exception(exitCause.throwable.message, exitCause.throwable)

internal fun Exception.unwrapSupervisorStartupFailure(): Exception =
    (this as? SupervisorStartupFailureException)?.exitCause?.throwable ?: this

internal fun Result<Int>.toSupervisorExitCause(stopRequested: Boolean): SupervisorExitCause {
    val failure = exceptionOrNull()
    if (failure != null) {
        return if (failure is CancellationException) {
            SupervisorExitCause.Cancellation
        } else {
            SupervisorExitCause.StartupFailure(
                failure as? Exception ?: IllegalStateException("Runtime supervisor failed", failure),
            )
        }
    }

    val code = getOrNull()
    return when {
        code == null -> SupervisorExitCause.Cancellation
        stopRequested && code == 0 -> SupervisorExitCause.ExpectedStop
        else -> SupervisorExitCause.Crash(code)
    }
}
