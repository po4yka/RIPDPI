package com.poyka.ripdpi.service.runtime.control

/**
 * The runtime control plane — the single seam through which features request
 * runtime changes (start, stop, restart, network-snapshot / policy / relay /
 * DNS refresh) instead of calling service lifecycle, proxy/VPN orchestration,
 * relay supervision, DNS, or telemetry coordinators directly.
 *
 * This is the **preferred** entry point for any new native-facing feature. It
 * does not replace the existing coordinators — it delegates to them via
 * [RuntimeControlActions] — and migration is incremental: direct coordinator
 * calls remain in place for paths not yet routed through here.
 *
 * Implementations must not change foreground-service callback behavior, runtime
 * persistence, or the `Mode` / `AppStatus` / `ServiceStatus` model.
 */
internal interface RuntimeControlPlane {
    /**
     * Execute [command] by delegating to the bound [RuntimeControlActions].
     *
     * Returns [RuntimeControlOutcome.Completed] when a runtime operation
     * serviced the command, or [RuntimeControlOutcome.Ignored] when none did.
     * Failures propagate as thrown exceptions — they are never swallowed.
     */
    suspend fun execute(command: RuntimeControlCommand): RuntimeControlOutcome
}
