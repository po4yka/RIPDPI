package com.poyka.ripdpi.services.leak

/**
 * Invokes the three revocation steps in deterministic order:
 *   1. Socket close
 *   2. TUN fd close
 *   3. Provider runtime stop
 *
 * Each step is represented by a nullable lambda so tests can inject
 * recording fakes without coupling to Android APIs.
 */
internal class OnRevokeOrchestrator(
    private val closeSocket: () -> Unit,
    private val closeTunFd: () -> Unit,
    private val stopProviderRuntime: () -> Unit,
) {
    private val steps: MutableList<RevokeStep> = mutableListOf()

    /**
     * Executes the three revocation steps in order and records each step taken.
     */
    fun onRevoke() {
        closeSocket()
        steps += RevokeStep.SocketClosed

        closeTunFd()
        steps += RevokeStep.TunFdClosed

        stopProviderRuntime()
        steps += RevokeStep.ProviderRuntimeStopped
    }

    /** Returns the ordered list of steps completed during the last [onRevoke] call. */
    fun completedSteps(): List<RevokeStep> = steps.toList()

    /** Clears the recorded step history. */
    fun reset() {
        steps.clear()
    }
}

/** Discriminates the three lifecycle resources closed during [OnRevokeOrchestrator.onRevoke]. */
internal enum class RevokeStep {
    SocketClosed,
    TunFdClosed,
    ProviderRuntimeStopped,
}
