package com.poyka.ripdpi.data

interface RuntimeCommandApplications {
    fun claimActivation(
        receipt: RuntimeActivationReceipt,
        identity: RuntimeAppliedUseIdentity,
    ): Boolean

    fun claimActivation(
        envelope: RuntimeActivationEnvelope,
        identity: RuntimeAppliedUseIdentity,
    ): RuntimeActivationReceipt?

    fun activationReceiptFromEnvelope(envelope: RuntimeActivationEnvelope): RuntimeActivationReceipt?

    fun claimContinuation(
        receipt: RuntimeActivationReceipt,
        lastPositiveIdentity: RuntimeAppliedUseIdentity,
        nextIdentity: RuntimeAppliedUseIdentity,
    ): Boolean

    fun acknowledgeApplied(
        original: RuntimeAppliedIntent,
        receipt: RuntimeAppliedUseReceipt,
    ): Boolean

    fun acknowledgedAttempt(
        original: RuntimeAppliedIntent,
        identity: RuntimeAppliedUseIdentity,
    ): RuntimeAppliedUseReceipt?

    fun terminateActivation(
        receipt: RuntimeActivationReceipt,
        identity: RuntimeAppliedUseIdentity,
    ): Boolean

    fun cancelPendingActivation(receipt: RuntimeActivationReceipt): Boolean

    fun finishOwnedRuntime(receipt: RuntimeActivationReceipt): RuntimeStopReceipt?
}

internal class CheckedRuntimeCommandApplications(
    private val cell: PauseAuthorityStateCell,
) : RuntimeCommandApplications,
    PauseAuthorityQueries by PauseAuthorityQueryReader(cell) {
    override fun claimActivation(
        receipt: RuntimeActivationReceipt,
        identity: RuntimeAppliedUseIdentity,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked false
            if (!matchesRuntimeAuthority(receipt, command) ||
                !isCommandPending(command, identity.mode)
            ) {
                return@locked false
            }
            cell.publish(
                before.copy(
                    command =
                        command.copy(
                            phase = RuntimeActivationPhase.Claimed(identity, command.initialClaimKind()),
                        ),
                ),
            )
            true
        }

    /** Service ingress validates serialized command identity before native startup. */

    override fun claimActivation(
        envelope: RuntimeActivationEnvelope,
        identity: RuntimeAppliedUseIdentity,
    ): RuntimeActivationReceipt? =
        cell.locked {
            val receipt = activationReceiptFromEnvelopeLocked(envelope) ?: return@locked null
            val before = cell.current()
            val command = before.command ?: return@locked null
            if (envelope.mode != identity.mode) return@locked null
            cell.publish(
                before.copy(
                    command =
                        command.copy(
                            phase = RuntimeActivationPhase.Claimed(identity, command.initialClaimKind()),
                        ),
                ),
            )
            receipt
        }

    override fun activationReceiptFromEnvelope(envelope: RuntimeActivationEnvelope): RuntimeActivationReceipt? =
        cell.locked { activationReceiptFromEnvelopeLocked(envelope) }

    private fun activationReceiptFromEnvelopeLocked(envelope: RuntimeActivationEnvelope): RuntimeActivationReceipt? {
        val command = cell.current().command ?: return null
        val identityMatches =
            command.generation == envelope.generation &&
                command.commandId == envelope.commandId && command.origin == envelope.origin
        return if (identityMatches && command.origin !is RuntimeCommandOrigin.PauseResume &&
            isCommandPending(command, envelope.mode)
        ) {
            RuntimeActivationReceipt(command)
        } else {
            null
        }
    }

    /** A same-session reconfiguration keeps the original command and advances only runtime revision. */

    override fun claimContinuation(
        receipt: RuntimeActivationReceipt,
        lastPositiveIdentity: RuntimeAppliedUseIdentity,
        nextIdentity: RuntimeAppliedUseIdentity,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked false
            val applied = command.phase as? RuntimeActivationPhase.Applied ?: return@locked false
            val followsPrevious =
                nextIdentity.runtimeId == lastPositiveIdentity.runtimeId &&
                    nextIdentity.mode == lastPositiveIdentity.mode &&
                    nextIdentity.revision > lastPositiveIdentity.revision
            if (!matchesRuntimeAuthority(receipt, command) || applied.identity != lastPositiveIdentity ||
                !followsPrevious
            ) {
                return@locked false
            }
            cell.publish(
                before.copy(
                    command =
                        command.copy(
                            phase =
                                RuntimeActivationPhase.Claimed(
                                    nextIdentity,
                                    RuntimeClaimKind.Continuation(lastPositiveIdentity),
                                ),
                        ),
                ),
            )
            true
        }

    override fun acknowledgeApplied(
        original: RuntimeAppliedIntent,
        receipt: RuntimeAppliedUseReceipt,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val command =
                before.command ?: return@locked false
            val measured = command.origin as? RuntimeCommandOrigin.MeasuredActivation
            if (measured != null && receipt.references != listOf(measured.reference)) return@locked false
            val capability = original.receipt
            if (!matchesRuntimeAuthority(capability, command) ||
                capability.mode.preferenceValue != receipt.identity.mode
            ) {
                return@locked false
            }
            if (command.phase is RuntimeActivationPhase.Applied) {
                val applied = command.phase as RuntimeActivationPhase.Applied
                if (applied.identity != receipt.identity || !original.matchesClaim(applied.kind, receipt.recordsUse)) {
                    return@locked false
                }
                return@locked before.profileUtility.acceptApplied(receipt) == before.profileUtility
            }
            if (command.phase !is RuntimeActivationPhase.Claimed ||
                (command.phase as RuntimeActivationPhase.Claimed).identity != receipt.identity
            ) {
                return@locked false
            }
            val claim = command.phase as RuntimeActivationPhase.Claimed
            if (!original.matchesClaim(claim.kind, receipt.recordsUse)) return@locked false
            if (!original.matchesAppliedState(before, command, receipt.identity)) return@locked false
            val utility = before.profileUtility.acceptApplied(receipt)
            cell.publish(
                before.copy(
                    pause = null,
                    desired = DesiredRuntimeState.Running,
                    desiredMode = receipt.identity.mode,
                    profileUtility = utility,
                    command = command.copy(phase = RuntimeActivationPhase.Applied(receipt.identity, claim.kind)),
                ),
            )
            true
        }

    /** A failed native attempt may terminate only its own claimed revision. */

    override fun acknowledgedAttempt(
        original: RuntimeAppliedIntent,
        identity: RuntimeAppliedUseIdentity,
    ): RuntimeAppliedUseReceipt? =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked null
            val applied = command.phase as? RuntimeActivationPhase.Applied ?: return@locked null
            if (!matchesRuntimeAuthority(original.receipt, command) || applied.identity != identity) return@locked null
            before.profileUtility.acknowledged
                .singleOrNull { it.identity == identity }
                ?.takeIf { original.matchesClaim(applied.kind, it.recordsUse) }
        }

    override fun terminateActivation(
        receipt: RuntimeActivationReceipt,
        identity: RuntimeAppliedUseIdentity,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked false
            if (!matchesRuntimeAuthority(receipt, command) ||
                (command.phase as? RuntimeActivationPhase.Claimed)?.identity != identity
            ) {
                return@locked false
            }
            terminateLocked(before, command)
            true
        }

    /** Framework rejection before claim cannot terminate a later claimed attempt. */

    override fun cancelPendingActivation(receipt: RuntimeActivationReceipt): Boolean =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked false
            if (!matchesRuntimeAuthority(receipt, command) ||
                command.phase !is RuntimeActivationPhase.Pending
            ) {
                return@locked false
            }
            terminateLocked(before, command)
            true
        }

    private fun terminateLocked(
        before: PauseAuthorityState,
        command: DurableCommandRecord,
    ) {
        val pause = before.pause?.takeIf { command.origin is RuntimeCommandOrigin.PauseResume }
        val recovery = before.keepsVerifiedRecoveryIntent(command)
        cell.publish(
            before.copy(
                desired =
                    when {
                        recovery -> DesiredRuntimeState.Running
                        pause != null -> DesiredRuntimeState.Paused
                        else -> DesiredRuntimeState.Stopped
                    },
                desiredMode = if (recovery) before.desiredMode else pause?.mode,
                pause = pause?.copy(phase = PausePhase.Deferred, failure = PauseFailure.RuntimeRejected),
                command = command.copy(phase = RuntimeActivationPhase.Terminated),
            ),
        )
    }

    override fun finishOwnedRuntime(receipt: RuntimeActivationReceipt): RuntimeStopReceipt? =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked null
            if (!matchesRuntimeAuthority(receipt, command) || command.phase !is RuntimeActivationPhase.Applied) {
                return@locked null
            }
            if (before.pause != null || before.desired != DesiredRuntimeState.Running) return@locked null
            val stopped = command.copy(phase = RuntimeActivationPhase.Terminated)
            cell.publish(
                before.copy(
                    desired = DesiredRuntimeState.Stopped,
                    desiredMode = null,
                    command = stopped,
                ),
            )
            RuntimeStopReceipt(stopped)
        }
}
