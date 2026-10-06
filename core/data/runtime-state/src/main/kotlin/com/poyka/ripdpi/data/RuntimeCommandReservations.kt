package com.poyka.ripdpi.data

import java.util.UUID

interface RuntimeStopReservations {
    fun reserveStop(): RuntimeStopReceipt

    fun reserveStopIfCurrent(expected: RuntimeAuthoritySnapshot): RuntimeStopReceipt?

    fun reserveResetStop(): RuntimeStopReceipt

    fun cancelMatchingPause(expected: PauseIntent): RuntimeStopReceipt?

    fun cancelMatchingCleanup(expected: PauseAuthorityRef): RuntimeStopReceipt?
}

interface RuntimeCommandReservations : RuntimeStopReservations {
    fun reserveStart(mode: Mode): RuntimeActivationReceipt

    fun invalidateForMutation(
        origin: ProfileMutationOrigin,
        mutationId: String,
        expected: PauseAuthorityRef,
    ): ProfileMutationOutcome

    fun reserveMeasuredActivation(
        expected: RuntimeAuthoritySnapshot,
        catalogGeneration: Long,
        reference: ProfileUtilityReference,
        commandId: String,
    ): ProfileActivationReceipt?

    fun retireMeasuredActivation(
        reference: PauseAuthorityRef,
        commandId: String,
    ): Boolean

    fun cancelProfileActivation(receipt: ProfileActivationReceipt): Boolean

    fun bindProfileActivation(
        receipt: ProfileActivationReceipt,
        mode: Mode,
    ): RuntimeActivationReceipt?
}

internal class CheckedRuntimeCommandReservations(
    private val cell: PauseAuthorityStateCell,
) : RuntimeCommandReservations,
    PauseAuthorityQueries by PauseAuthorityQueryReader(cell) {
    override fun reserveStart(mode: Mode): RuntimeActivationReceipt =
        cell.locked {
            val before = cell.current()
            val command =
                nextRuntimeCommand(
                    before,
                    UUID.randomUUID().toString(),
                    RuntimeCommandOrigin.UserStart,
                    RuntimeActivationPhase.Pending(mode.preferenceValue),
                )
            cell.publish(
                before.copy(
                    generation = command.generation,
                    pause = null,
                    desired = DesiredRuntimeState.Running,
                    desiredMode = mode.preferenceValue,
                    command = command,
                ),
            )
            RuntimeActivationReceipt(command)
        }

    override fun reserveStop(): RuntimeStopReceipt =
        reserveStopped(UUID.randomUUID().toString(), RuntimeCommandOrigin.UserStop)

    override fun reserveStopIfCurrent(expected: RuntimeAuthoritySnapshot): RuntimeStopReceipt? =
        cell.locked {
            if (snapshotAuthority() != expected) {
                null
            } else {
                reserveStoppedLocked(cell.current(), UUID.randomUUID().toString(), RuntimeCommandOrigin.UserStop)
            }
        }

    override fun reserveResetStop(): RuntimeStopReceipt =
        cell.locked {
            val before = cell.state.value
            if (before == null) {
                val command =
                    DurableCommandRecord(
                        1,
                        UUID.randomUUID().toString(),
                        RuntimeCommandOrigin.ResetStop,
                        RuntimeActivationPhase.Terminated,
                    )
                cell.publish(
                    PauseAuthorityState(
                        1,
                        null,
                        null,
                        desired = DesiredRuntimeState.Stopped,
                        profileUtility = ProfileUtilityState.empty(),
                        command = command,
                    ),
                )
                RuntimeStopReceipt(command)
            } else {
                reserveStoppedLocked(before, UUID.randomUUID().toString(), RuntimeCommandOrigin.ResetStop)
            }
        }

    private fun reserveStopped(
        commandId: String,
        origin: RuntimeCommandOrigin,
    ): RuntimeStopReceipt =
        cell.locked {
            reserveStoppedLocked(cell.current(), commandId, origin)
        }

    private fun reserveStoppedLocked(
        before: PauseAuthorityState,
        commandId: String,
        origin: RuntimeCommandOrigin,
    ): RuntimeStopReceipt {
        val command = nextRuntimeCommand(before, commandId, origin, RuntimeActivationPhase.Terminated)
        cell.publish(
            before.copy(
                generation = command.generation,
                pause = null,
                desired = DesiredRuntimeState.Stopped,
                desiredMode = null,
                command = command,
            ),
        )
        return RuntimeStopReceipt(command)
    }

    /** Token-bound cancellation cannot consume a newer pause. */

    override fun cancelMatchingPause(expected: PauseIntent): RuntimeStopReceipt? =
        cell.locked {
            val before = cell.current()
            if (before.generation != expected.generation || before.pause?.token != expected.token) {
                null
            } else {
                reserveStoppedLocked(before, UUID.randomUUID().toString(), RuntimeCommandOrigin.UserStop)
            }
        }

    /** A cleanup retry owns only the exact stopped generation and never restores a cancelled lease. */

    override fun cancelMatchingCleanup(expected: PauseAuthorityRef): RuntimeStopReceipt? =
        cell.locked {
            val before = cell.current()
            if (before.generation != expected.generation || before.pause != null ||
                before.desired != DesiredRuntimeState.Stopped
            ) {
                null
            } else {
                reserveStoppedLocked(before, UUID.randomUUID().toString(), RuntimeCommandOrigin.UserStop)
            }
        }

    override fun invalidateForMutation(
        origin: ProfileMutationOrigin,
        mutationId: String,
        expected: PauseAuthorityRef,
    ): ProfileMutationOutcome =
        cell.locked {
            val before = cell.current()
            if (!origin.supersedesPause) return@locked ProfileMutationOutcome.NonSuperseding
            if (before.lastMutationId == mutationId) {
                val command = before.command
                val matchingReplay =
                    command?.let {
                        it.commandId == mutationId && it.generation == before.generation &&
                            (it.origin as? RuntimeCommandOrigin.ProfileMutation)?.mutationOrigin == origin
                    } == true
                return@locked if (matchingReplay) {
                    ProfileMutationOutcome.Reserved(mutationReceiptFor(checkNotNull(command)))
                } else {
                    ProfileMutationOutcome.Superseded
                }
            }
            if (before.generation != expected.generation) return@locked ProfileMutationOutcome.Superseded
            val phase =
                if (origin ==
                    ProfileMutationOrigin.ExplicitActivation
                ) {
                    RuntimeActivationPhase.Unbound
                } else {
                    RuntimeActivationPhase.Terminated
                }
            val command =
                nextRuntimeCommand(before, mutationId, RuntimeCommandOrigin.ProfileMutation(origin, mutationId), phase)
            val stopped = true
            cell.publish(
                before.copy(
                    generation = command.generation,
                    pause = null,
                    lastMutationId = mutationId,
                    lastMutationAuthority = PauseAuthorityRef(command.generation),
                    desired = if (stopped) DesiredRuntimeState.Stopped else before.desired,
                    desiredMode = if (stopped) null else before.desiredMode,
                    command = command,
                ),
            )
            ProfileMutationOutcome.Reserved(mutationReceiptFor(checkNotNull(command)))
        }

    override fun reserveMeasuredActivation(
        expected: RuntimeAuthoritySnapshot,
        catalogGeneration: Long,
        reference: ProfileUtilityReference,
        commandId: String,
    ): ProfileActivationReceipt? =
        cell.locked {
            val before = cell.current()
            if (snapshotAuthority() != expected) return@locked null
            val utility = before.profileUtility
            if (!utility.catalogReady || utility.catalogGeneration != catalogGeneration ||
                reference !in utility.catalog
            ) {
                return@locked null
            }
            val command =
                nextRuntimeCommand(
                    before,
                    commandId,
                    RuntimeCommandOrigin.MeasuredActivation(commandId, reference),
                    RuntimeActivationPhase.Unbound,
                )
            cell.publish(
                before.copy(
                    generation = command.generation,
                    pause = null,
                    desired = DesiredRuntimeState.Stopped,
                    desiredMode = null,
                    lastMutationId = commandId,
                    lastMutationAuthority = PauseAuthorityRef(command.generation),
                    command = command,
                ),
            )
            ProfileActivationReceipt(command)
        }

    override fun retireMeasuredActivation(
        reference: PauseAuthorityRef,
        commandId: String,
    ): Boolean =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked false
            if (command.generation != reference.generation || command.commandId != commandId ||
                command.origin !is RuntimeCommandOrigin.MeasuredActivation
            ) {
                return@locked false
            }
            if (command.phase !is RuntimeActivationPhase.Pending && command.phase != RuntimeActivationPhase.Unbound) {
                return@locked false
            }
            cell.publish(
                before.copy(
                    command = command.copy(phase = RuntimeActivationPhase.Terminated),
                    desired = DesiredRuntimeState.Stopped,
                    desiredMode = null,
                ),
            )
            true
        }

    override fun cancelProfileActivation(receipt: ProfileActivationReceipt): Boolean =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked false
            if (!matchesRuntimeAuthority(receipt, command) || command.phase != RuntimeActivationPhase.Unbound) {
                return@locked false
            }
            cell.publish(before.copy(command = command.copy(phase = RuntimeActivationPhase.Terminated)))
            true
        }

    override fun bindProfileActivation(
        receipt: ProfileActivationReceipt,
        mode: Mode,
    ): RuntimeActivationReceipt? =
        cell.locked {
            val before = cell.current()
            val command = before.command ?: return@locked null
            if (!matchesRuntimeAuthority(receipt, command) ||
                command.phase != RuntimeActivationPhase.Unbound ||
                !command.activatesProfile()
            ) {
                return@locked null
            }
            val pending = command.copy(phase = RuntimeActivationPhase.Pending(mode.preferenceValue))
            cell.publish(before.copy(desired = DesiredRuntimeState.Stopped, desiredMode = null, command = pending))
            RuntimeActivationReceipt(pending)
        }
}
