package com.poyka.ripdpi.service.runtime.control

import com.poyka.ripdpi.data.Mode

/**
 * A typed request for a runtime change, routed through [RuntimeControlPlane].
 *
 * Each command is a coarse, declarative description of *what* the caller wants
 * — start, stop, restart, or refresh a slice of the runtime — tagged with a
 * [RuntimeControlReason]. Commands carry no behavior; the control plane maps
 * them onto existing coordinators via [RuntimeControlActions]. This is the
 * single seam features should use instead of reaching into service lifecycle,
 * proxy/VPN orchestration, relay supervision, DNS, or telemetry directly.
 */
internal sealed interface RuntimeControlCommand {
    /** Why this command was issued — carried for telemetry, never for dispatch. */
    val reason: RuntimeControlReason

    /** Start the runtime in [mode]. */
    data class StartRuntime(
        val mode: Mode,
        override val reason: RuntimeControlReason,
    ) : RuntimeControlCommand

    /** Stop the runtime. */
    data class StopRuntime(
        override val reason: RuntimeControlReason,
    ) : RuntimeControlCommand

    /** Stop then start the runtime, preserving the active mode. */
    data class RestartRuntime(
        override val reason: RuntimeControlReason,
    ) : RuntimeControlCommand

    /** Push a fresh native network snapshot into the running runtime. */
    data class ApplyNetworkSnapshot(
        override val reason: RuntimeControlReason,
    ) : RuntimeControlCommand

    /** Apply a connection-policy [patch] to the running runtime. */
    data class ApplyPolicyPatch(
        override val reason: RuntimeControlReason,
        val patch: RuntimePolicyPatch,
    ) : RuntimeControlCommand

    /** Refresh the upstream relay layer without tearing down the base path. */
    data class RefreshRelay(
        override val reason: RuntimeControlReason,
    ) : RuntimeControlCommand

    /** Refresh DNS resolution / the encrypted-DNS substrate for the running runtime. */
    data class RefreshDns(
        override val reason: RuntimeControlReason,
    ) : RuntimeControlCommand
}
