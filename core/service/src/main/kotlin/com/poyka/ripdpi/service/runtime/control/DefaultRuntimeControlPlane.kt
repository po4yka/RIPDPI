package com.poyka.ripdpi.service.runtime.control

import co.touchlab.kermit.Logger

/**
 * Default [RuntimeControlPlane] — a thin dispatcher that maps each
 * [RuntimeControlCommand] onto the matching [RuntimeControlActions] operation
 * and logs the command for an audit trail.
 *
 * It holds no runtime state and makes no decisions: all behavior lives in the
 * injected [actions]. Exceptions from an action propagate unchanged so a caller
 * routed through the seam keeps the exact error semantics of the direct
 * coordinator call it replaced.
 */
internal class DefaultRuntimeControlPlane(
    private val actions: RuntimeControlActions,
) : RuntimeControlPlane {
    override suspend fun execute(command: RuntimeControlCommand): RuntimeControlOutcome {
        Logger.d { "RuntimeControlPlane received $command" }
        val outcome =
            when (command) {
                is RuntimeControlCommand.StartRuntime -> actions.startRuntime(command.mode, command.reason)
                is RuntimeControlCommand.StopRuntime -> actions.stopRuntime(command.reason)
                is RuntimeControlCommand.RestartRuntime -> actions.restartRuntime(command.reason)
                is RuntimeControlCommand.ApplyNetworkSnapshot -> actions.applyNetworkSnapshot(command.reason)
                is RuntimeControlCommand.ApplyPolicyPatch -> actions.applyPolicyPatch(command.reason, command.patch)
                is RuntimeControlCommand.RefreshRelay -> actions.refreshRelay(command.reason)
                is RuntimeControlCommand.RefreshDns -> actions.refreshDns(command.reason)
            }
        Logger.d { "RuntimeControlPlane completed $command -> $outcome" }
        return outcome
    }
}
