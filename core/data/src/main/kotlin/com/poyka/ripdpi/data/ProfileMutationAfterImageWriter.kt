package com.poyka.ripdpi.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Writes one encrypted after-image while retaining the coordinator's recovery lock and fences. */
internal class ProfileMutationAfterImageWriter(
    private val mutex: Mutex,
    private val recover: suspend () -> Unit,
    private val stores: ProfileMutationStores,
    private val journal: ProfileMutationJournal,
    private val pauseAuthority: PauseIntentAuthority,
    private val intentProjection: ProfileMutationIntentProjection,
    private val replayWriter: ProfileMutationReplayWriter,
    private val catalog: ProfileUtilityCatalogPublisher,
    private val mutationGeneration: ProfileMutationGenerationPublisher,
    private val warpRevisions: WarpRuntimeMutationRevisions,
) {
    suspend fun execute(
        preparation: ProfileMutationPreparation,
        intent: ProfileMutationIntent,
    ) = mutex.withLock {
        recover()
        executeRecovered(preparation, intent)
    }

    suspend fun executeRecovered(
        preparation: ProfileMutationPreparation,
        intent: ProfileMutationIntent,
    ): ProfileMutationOutcome {
        val beforeWarp = (intent as? WarpUpsertIntent)?.let { stores.warpCredentials.load(it.profile.id) }
        val beforeProfile = (intent as? WarpUpsertIntent)?.let { stores.warpProfiles.load(it.profile.id) }
        val pending = intentProjection.pending(preparation.origin, preparation.expectedPauseAuthority, intent)
        journal.prepare(pending)
        val outcome =
            pauseAuthority.invalidateForMutation(
                preparation.origin,
                pending.mutationId,
                checkNotNull(pending.expectedPauseAuthority),
            )
        if (intent.affectsUtilityCatalog()) catalog.invalidate()
        if (intent.ownsProviderSelection(preparation.origin)) {
            val receipt = (outcome as? ProfileMutationOutcome.Reserved)?.receipt as? ProfileActivationReceipt
            if (receipt != null) replayWriter.replayOwned(intent, receipt)
        } else {
            replayWriter.replay(intent)
        }
        catalog.publish()
        journal.complete(pending.mutationId)
        publishCommittedWarpRevision(warpRevisions, intent, beforeWarp, beforeProfile)
        mutationGeneration.completed()
        return outcome
    }

    /** Captured compensation/Stop writes metadata without reserving or replacing a user command. */
    suspend fun executeOwnedMetadata(
        receipt: DurableCommandReceipt,
        intent: ProfileMutationIntent,
    ): Boolean {
        if (!pauseAuthority.isCurrent(receipt)) return false
        val pending = intentProjection.pending(ProfileMutationOrigin.Compensation, receipt.authority, intent)
        journal.prepare(pending)
        if (pauseAuthority.isCurrent(receipt)) replayWriter.replay(intent)
        journal.complete(pending.mutationId)
        catalog.publish()
        mutationGeneration.completed()
        return pauseAuthority.isCurrent(receipt)
    }

    suspend fun executeWithCompensation(
        preparation: ProfileMutationPreparation,
        target: ProfileMutationIntent,
        rollback: ProfileMutationIntent,
    ) = mutex.withLock {
        recover()
        val targetPending = intentProjection.pending(preparation.origin, preparation.expectedPauseAuthority, target)
        journal.prepare(targetPending)
        val outcome =
            pauseAuthority.invalidateForMutation(
                preparation.origin,
                targetPending.mutationId,
                checkNotNull(targetPending.expectedPauseAuthority),
            )
        val failure =
            runCatching {
                if (target.affectsUtilityCatalog()) catalog.invalidate()
                replayWriter.replay(target)
                catalog.publish()
                journal.complete(targetPending.mutationId)
                warpRevisions.invalidateAll()
                mutationGeneration.completed()
            }.exceptionOrNull()
        if (failure != null) {
            val rollbackFailure =
                runCatching {
                    withContext(NonCancellable) {
                        val rollbackPending =
                            intentProjection.pending(
                                ProfileMutationOrigin.Compensation,
                                pauseAuthority.reference(),
                                rollback,
                            )
                        journal.replace(targetPending.mutationId, rollbackPending)
                        if (rollback.affectsUtilityCatalog()) catalog.invalidate()
                        replayWriter.replay(rollback)
                        catalog.publish()
                        journal.complete(rollbackPending.mutationId)
                        warpRevisions.invalidateAll()
                        mutationGeneration.completed()
                    }
                }.exceptionOrNull()
            if (rollbackFailure != null && rollbackFailure !== failure) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        outcome
    }
}

internal fun ProfileMutationIntent.ownsProviderSelection(origin: ProfileMutationOrigin): Boolean =
    when (this) {
        is SelectorSelectIntent, is StandaloneAwgSelectIntent -> true
        is WarpUpsertIntent -> activate && origin == ProfileMutationOrigin.ExplicitActivation
        else -> false
    }
