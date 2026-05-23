package com.poyka.ripdpi.service.runtime.control

import com.poyka.ripdpi.data.Mode

/**
 * The delegation SPI behind [RuntimeControlPlane]: one suspend operation per
 * [RuntimeControlCommand]. An implementation binds each operation to an existing
 * coordinator / supervisor call — the control plane itself only dispatches.
 *
 * Splitting the seam (commands) from its wiring (actions) lets the runtime layer
 * be migrated incrementally: a process-level implementation can service the
 * lifecycle operations today, and a session-scoped implementation can service
 * the in-session operations later, without the command vocabulary changing.
 */
internal interface RuntimeControlActions {
    /** Start the runtime in [mode]. */
    suspend fun startRuntime(
        mode: Mode,
        reason: RuntimeControlReason,
    ): RuntimeControlOutcome

    /** Stop the runtime. */
    suspend fun stopRuntime(reason: RuntimeControlReason): RuntimeControlOutcome

    /** Stop then start the runtime, preserving the active mode. */
    suspend fun restartRuntime(reason: RuntimeControlReason): RuntimeControlOutcome

    /** Push a fresh native network snapshot into the running runtime. */
    suspend fun applyNetworkSnapshot(reason: RuntimeControlReason): RuntimeControlOutcome

    /** Apply a connection-policy [patch] to the running runtime. */
    suspend fun applyPolicyPatch(
        reason: RuntimeControlReason,
        patch: RuntimePolicyPatch,
    ): RuntimeControlOutcome

    /** Refresh the upstream relay layer without tearing down the base path. */
    suspend fun refreshRelay(reason: RuntimeControlReason): RuntimeControlOutcome

    /** Refresh DNS resolution / the encrypted-DNS substrate for the running runtime. */
    suspend fun refreshDns(reason: RuntimeControlReason): RuntimeControlOutcome
}
