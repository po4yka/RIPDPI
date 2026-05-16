package com.poyka.ripdpi.services.lifecycle

/**
 * Handles a core (provider runtime) process crash and returns the appropriate
 * fail-closed lifecycle state.
 *
 * A crash MUST never leave the service in [VpnLifecycleState.Connected]; it
 * transitions to either [VpnLifecycleState.Reconnecting] (when a retry is
 * feasible) or [VpnLifecycleState.Blocked] (when retries are exhausted).
 */
class CoreCrashHandler(
    private val maxRetries: Int = 3,
) {
    private var crashCount: Int = 0

    /**
     * Records a crash and returns the resulting [VpnLifecycleState].
     *
     * Returns [VpnLifecycleState.Reconnecting] until [maxRetries] is reached,
     * then returns [VpnLifecycleState.Blocked] with [BlockedReason.CoreCrash].
     */
    fun handleCrash(): VpnLifecycleState {
        crashCount += 1
        return if (crashCount <= maxRetries) {
            VpnLifecycleState.Reconnecting
        } else {
            VpnLifecycleState.Blocked(BlockedReason.CoreCrash)
        }
    }

    /** Resets the crash counter — call after a successful reconnect. */
    fun reset() {
        crashCount = 0
    }

    val currentCrashCount: Int
        get() = crashCount
}
