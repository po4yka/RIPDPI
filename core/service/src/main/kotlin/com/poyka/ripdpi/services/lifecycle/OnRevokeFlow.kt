package com.poyka.ripdpi.services.lifecycle

/**
 * Executes the fail-closed revocation sequence in deterministic order:
 *   1. Close TUN fd
 *   2. Close tunnel sockets
 *   3. Stop provider runtimes
 *   4. Stop local inbounds
 *
 * The flow is idempotent: calling [execute] more than once is safe — subsequent
 * calls are no-ops after the first execution completes.
 */
class OnRevokeFlow(
    private val closeTunFd: () -> Unit,
    private val closeTunnelSockets: () -> Unit,
    private val stopProviderRuntime: () -> Unit,
    private val stopLocalInbounds: () -> Unit,
) {
    private var executed: Boolean = false

    /**
     * Executes the four-step revocation sequence.
     * Idempotent — safe to call multiple times (OEM double-callback defence).
     */
    fun execute() {
        if (executed) return
        executed = true

        closeTunFd()
        closeTunnelSockets()
        stopProviderRuntime()
        stopLocalInbounds()
    }

    /** Returns the steps completed in order, for test assertions. */
    fun wasExecuted(): Boolean = executed
}

/** Ordered revocation steps recorded during [OnRevokeFlow.execute]. */
enum class RevokeFlowStep {
    TunFdClosed,
    TunnelSocketsClosed,
    ProviderRuntimeStopped,
    LocalInboundsStopped,
}
