package com.poyka.ripdpi.data

import java.util.UUID

interface RuntimeCommandPolicies {
    fun authorizeStartupFallback(expected: RuntimeAuthoritySnapshot): RuntimeActivationReceipt?

    fun authorizeRecovery(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ): RuntimeActivationReceipt?

    fun authorizeBootPolicyStart(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ): RuntimeActivationReceipt?
}

internal class CheckedRuntimeCommandPolicies(
    private val cell: PauseAuthorityStateCell,
) : RuntimeCommandPolicies,
    PauseAuthorityQueries by PauseAuthorityQueryReader(cell) {
    override fun authorizeStartupFallback(expected: RuntimeAuthoritySnapshot): RuntimeActivationReceipt? =
        cell.locked {
            val before = cell.current()
            val captured = expected.command ?: return@locked null
            val current = before.command ?: return@locked null
            val ordinaryStart =
                captured.origin == RuntimeCommandOrigin.UserStart ||
                    captured.origin is RuntimeCommandOrigin.StartupFallback
            val mode =
                when (val phase = captured.phase) {
                    is RuntimeActivationPhase.Pending -> phase.mode
                    is RuntimeActivationPhase.Claimed -> phase.identity.mode
                    else -> null
                }
            val sameOwner =
                before.generation == expected.reference.generation &&
                    current.commandId == captured.commandId && current.origin == captured.origin
            val validSource = ordinaryStart && sameOwner && mode == Mode.VPN.preferenceValue
            if (!validSource || before.pause != null || current.phase != RuntimeActivationPhase.Terminated) {
                return@locked null
            }
            val command =
                DurableCommandRecord(
                    before.generation,
                    UUID.randomUUID().toString(),
                    RuntimeCommandOrigin.StartupFallback(captured.commandId),
                    RuntimeActivationPhase.Pending(mode),
                )
            cell.publish(before.copy(desired = DesiredRuntimeState.Running, desiredMode = mode, command = command))
            RuntimeActivationReceipt(command)
        }

    override fun authorizeRecovery(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ): RuntimeActivationReceipt? =
        cell.locked {
            if (snapshotAuthority() != expected || !allowsRecovery(expected.reference, mode)) return@locked null
            val before = cell.current()
            val command =
                DurableCommandRecord(
                    before.generation,
                    UUID.randomUUID().toString(),
                    RuntimeCommandOrigin.Recovery(
                        before.command?.commandId,
                        before.command?.recoveryEvidence(mode, before.profileUtility),
                    ),
                    RuntimeActivationPhase.Pending(mode.preferenceValue),
                )
            cell.publish(before.copy(command = command))
            RuntimeActivationReceipt(command)
        }

    override fun authorizeBootPolicyStart(
        mode: Mode,
        expected: RuntimeAuthoritySnapshot,
    ): RuntimeActivationReceipt? =
        cell.locked {
            if ((snapshotAuthority() != expected) || expected.pause != null) return@locked null
            val before = cell.current()
            val command =
                nextRuntimeCommand(
                    before,
                    UUID.randomUUID().toString(),
                    RuntimeCommandOrigin.BootPolicy,
                    RuntimeActivationPhase.Pending(mode.preferenceValue),
                )
            cell.publish(
                before.copy(
                    generation = command.generation,
                    desired = DesiredRuntimeState.Running,
                    desiredMode = mode.preferenceValue,
                    command = command,
                ),
            )
            RuntimeActivationReceipt(command)
        }
}
