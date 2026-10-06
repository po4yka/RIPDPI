package com.poyka.ripdpi.data

internal fun nextRuntimeCommand(
    before: PauseAuthorityState,
    id: String,
    origin: RuntimeCommandOrigin,
    phase: RuntimeActivationPhase,
) = DurableCommandRecord(Math.addExact(before.generation, 1), id, origin, phase)

internal fun DurableCommandRecord.activatesProfile(): Boolean =
    origin is RuntimeCommandOrigin.MeasuredActivation ||
        (origin as? RuntimeCommandOrigin.ProfileMutation)?.mutationOrigin == ProfileMutationOrigin.ExplicitActivation

internal fun mutationReceiptFor(command: DurableCommandRecord): DurableCommandReceipt =
    if (command.activatesProfile()) ProfileActivationReceipt(command) else RuntimeStopReceipt(command)

internal fun isCommandPending(
    command: DurableCommandRecord,
    mode: String,
) = (command.phase as? RuntimeActivationPhase.Pending)?.mode == mode

internal fun matchesRuntimeAuthority(
    receipt: DurableCommandReceipt,
    command: DurableCommandRecord,
) = receipt.authority.generation == command.generation && receipt.commandId == command.commandId &&
    receipt.origin == command.origin

internal fun matchesRuntimeAuthority(
    first: PauseIntent,
    second: PauseIntent,
) = first.generation == second.generation && first.token == second.token

internal fun RuntimeAppliedIntent.matchesAppliedState(
    before: PauseAuthorityState,
    command: DurableCommandRecord,
    identity: RuntimeAppliedUseIdentity,
): Boolean {
    val mode = identity.mode
    val runningMode =
        before.pause == null && before.desired == DesiredRuntimeState.Running && before.desiredMode == mode
    return when (this) {
        is RuntimeAppliedIntent.Activation -> {
            before.pause == null &&
                when (val origin = command.origin) {
                    RuntimeCommandOrigin.UserStart,
                    RuntimeCommandOrigin.BootPolicy,
                    is RuntimeCommandOrigin.StartupFallback,
                    -> {
                        before.desired == DesiredRuntimeState.Running &&
                            before.desiredMode == mode
                    }

                    is RuntimeCommandOrigin.MeasuredActivation -> {
                        before.desired == DesiredRuntimeState.Stopped
                    }

                    is RuntimeCommandOrigin.ProfileMutation -> {
                        origin.mutationOrigin == ProfileMutationOrigin.ExplicitActivation &&
                            before.desired == DesiredRuntimeState.Stopped
                    }

                    else -> {
                        false
                    }
                }
        }

        is RuntimeAppliedIntent.Resume -> {
            before.pause?.let {
                matchesRuntimeAuthority(it, intent) &&
                    it.phase == PausePhase.Resuming
            } ==
                true
        }

        is RuntimeAppliedIntent.Continuation -> {
            lastPositiveIdentity.runtimeId ==
                identity.runtimeId &&
                lastPositiveIdentity.mode == mode
        }

        is RuntimeAppliedIntent.Recovery -> {
            command.origin is RuntimeCommandOrigin.Recovery && runningMode &&
                this.mode.preferenceValue == mode
        }
    }
}
