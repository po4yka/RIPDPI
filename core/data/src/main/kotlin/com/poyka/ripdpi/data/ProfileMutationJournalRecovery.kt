package com.poyka.ripdpi.data

/** Executes only while the owning coordinator holds its mutation mutex. */
internal class ProfileMutationJournalRecovery(
    private val recoveryReader: ProfileMutationJournalRecoveryReader,
    private val replay: suspend (ProfileMutationIntent) -> Unit,
    private val replayMeasuredReconstructed: suspend (MeasuredUtilitySelectionIntent) -> Unit,
    private val replayReconstructed: suspend (ProfileMutationIntent, ProfileActivationReceipt) -> Unit,
    private val journal: ProfileMutationJournal,
    private val pauseAuthority: PauseIntentAuthority,
    private val mutationGeneration: ProfileMutationGenerationPublisher,
    private val catalog: ProfileUtilityCatalogPublisher,
    private val publishRecovery: (ProfileMutationIntent) -> Unit,
) {
    suspend fun recover() {
        recoveryReader.read()?.let { recovered ->
            val outcome = recoverAuthority(recovered)
            val legacy = recovered.pending.schemaVersion == 1
            if (!legacy && recovered.intent.affectsUtilityCatalog()) catalog.invalidate()
            if (!legacy && recovered.intent is MeasuredUtilitySelectionIntent) {
                replayMeasuredReconstructed(recovered.intent)
            } else if (!legacy && recovered.intent.ownsProviderSelection(checkNotNull(recovered.pending.origin))) {
                val receipt = (outcome as? ProfileMutationOutcome.Reserved)?.receipt as? ProfileActivationReceipt
                if (receipt != null) {
                    retireReconstructedActivation(receipt)
                    replayReconstructed(recovered.intent, receipt)
                }
            } else {
                replay(recovered.intent)
            }
            if (!legacy) catalog.publish()
            journal.complete(recovered.pending.mutationId)
            publishRecovery(recovered.intent)
            mutationGeneration.completed()
        }
        pauseAuthority.initializeAfterMigration()
        catalog.publish()
    }

    private fun recoverAuthority(recovered: RecoverablePendingMutation): ProfileMutationOutcome? =
        if (recovered.pending.schemaVersion == 1) {
            check(!pauseAuthority.isInitialized()) { "Legacy profile journal exists after pause migration" }
            null
        } else {
            pauseAuthority.initializeAfterMigration()
            if (recovered.intent is MeasuredUtilitySelectionIntent) {
                retireMeasuredReservation(recovered.intent.reservation)
                ProfileMutationOutcome.NonSuperseding
            } else {
                pauseAuthority.invalidateForMutation(
                    checkNotNull(recovered.pending.origin),
                    recovered.pending.mutationId,
                    checkNotNull(recovered.pending.expectedPauseAuthority),
                )
            }
        }

    private fun retireReconstructedActivation(receipt: ProfileActivationReceipt) {
        val command = pauseAuthority.snapshotAuthority().command ?: return
        if (!pauseAuthority.isCurrent(receipt)) return
        when (val phase = command.phase) {
            RuntimeActivationPhase.Unbound -> {
                pauseAuthority.cancelProfileActivation(receipt)
            }

            is RuntimeActivationPhase.Pending -> {
                val activation =
                    pauseAuthority.activationReceiptFromEnvelope(
                        RuntimeActivationEnvelope(command.generation, command.commandId, command.origin, phase.mode),
                    )
                if (activation != null) pauseAuthority.cancelPendingActivation(activation)
            }

            else -> {
                Unit
            }
        }
    }

    /** Recovery finishes persistence only and never creates a fresh measured activation. */
    private fun retireMeasuredReservation(reservation: MeasuredActivationReservation) {
        val current =
            pauseAuthority.snapshotAuthority().command?.takeIf {
                it.generation == reservation.authority.generation && it.commandId == reservation.commandId
            } ?: return
        val origin = current.origin as? RuntimeCommandOrigin.MeasuredActivation
        if (origin == null) return
        check(origin.mutationId == reservation.commandId)
        when (current.phase) {
            RuntimeActivationPhase.Unbound -> {
                pauseAuthority.retireMeasuredActivation(reservation.authority, reservation.commandId)
            }

            is RuntimeActivationPhase.Pending -> {
                val receipt =
                    pauseAuthority.activationReceiptFromEnvelope(
                        RuntimeActivationEnvelope(
                            current.generation,
                            current.commandId,
                            current.origin,
                            reservation.mode,
                        ),
                    )
                if (receipt != null) pauseAuthority.cancelPendingActivation(receipt)
            }

            else -> {
                Unit
            } // reconstructed Claim is already terminated by mandatory initialization
        }
    }
}

/** Utility references cover native relay/Xray catalogs and selector members, not WARP/AWG provisioning stores. */
internal fun ProfileMutationIntent.affectsUtilityCatalog(): Boolean =
    when (family) {
        ProfileMutationFamily.Relay, ProfileMutationFamily.Xray, ProfileMutationFamily.Backup -> true
        ProfileMutationFamily.Warp, ProfileMutationFamily.Awg -> false
    }
