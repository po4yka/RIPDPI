package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.selector.SelectorChoiceOrigin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** A profile operation has one SQLite commit; runtime publication follows that commit. */
internal class ProfileMutationAfterImageWriter(
    private val runtime: ProfileMutationRuntime,
) {
    suspend fun execute(
        preparation: ProfileMutationPreparation,
        intent: ProfileMutationIntent,
    ) = runtime.mutex.withLock {
        runtime.recover()
        executeRecovered(preparation, intent)
    }

    // Reservation cleanup must also run for cancellation and fatal failures before rethrowing.
    @Suppress("TooGenericExceptionCaught")
    suspend fun executeRecovered(
        preparation: ProfileMutationPreparation,
        intent: ProfileMutationIntent,
        validate: (com.poyka.ripdpi.data.persistence.ProfileRows) -> Unit = {},
    ): ProfileMutationOutcome {
        var reserved: ProfileActivationReceipt? = null
        if (intent.affectsUtilityCatalog()) runtime.catalog.invalidate()
        try {
            val committed =
                runtime.database.withAuthority(
                    runtime.pauseAuthority,
                    afterCommit = { committed ->
                        if (intent is SelectorSelectIntent) {
                            val reservation = committed.outcome as? ProfileMutationOutcome.Reserved
                            val receipt = reservation?.receipt as? ProfileActivationReceipt
                            receipt?.let {
                                runtime.activeGroup.publishCommittedChoice(
                                    intent.groupId,
                                    SelectorChoiceOrigin.Manual(it),
                                )
                            }
                        }
                    },
                ) { rows ->
                    validate(rows)
                    val before =
                        (intent as? WarpUpsertIntent)?.let {
                            loadReplaceableCredential { rows.warpCredentials.load(it.profile.id) }
                        }
                    val beforeProfile = (intent as? WarpUpsertIntent)?.let { rows.warpProfiles.load(it.profile.id) }
                    val outcome =
                        runtime.pauseAuthority.invalidateForMutation(
                            preparation.origin,
                            UUID.randomUUID().toString(),
                            preparation.expectedPauseAuthority,
                        )
                    val writer = ProfileMutationRowWriter(rows, runtime.pauseAuthority)
                    reserved = (outcome as? ProfileMutationOutcome.Reserved)?.receipt as? ProfileActivationReceipt
                    if (intent.ownsProviderSelection(preparation.origin)) {
                        reserved?.let { writer.applyOwned(intent, it) }
                    } else {
                        writer.apply(intent)
                    }
                    CommittedMutation(outcome, before, beforeProfile)
                }
            publishCommittedWarpRevision(runtime.warpRevisions, intent, committed.before, committed.beforeProfile)
            runtime.catalog.publish()
            runtime.mutationGeneration.completed()
            return committed.outcome
        } catch (failure: Throwable) {
            reserved?.let { runtime.pauseAuthority.cancelProfileActivation(it) }
            withContext(NonCancellable) {
                runCatching { runtime.catalog.publish() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    suspend fun executeOwnedMetadata(
        receipt: DurableCommandReceipt,
        intent: ProfileMutationIntent,
    ): Boolean {
        val committed =
            runtime.database.withAuthority(runtime.pauseAuthority) { rows ->
                if (!runtime.pauseAuthority.isCurrent(receipt)) {
                    false
                } else {
                    ProfileMutationRowWriter(rows, runtime.pauseAuthority).apply(intent)
                    true
                }
            }
        if (committed) {
            runtime.catalog.publish()
            runtime.mutationGeneration.completed()
        }
        return committed && runtime.pauseAuthority.isCurrent(receipt)
    }
}

private data class CommittedMutation(
    val outcome: ProfileMutationOutcome,
    val before: WarpCredentials?,
    val beforeProfile: WarpProfile?,
)

internal fun ProfileMutationIntent.ownsProviderSelection(origin: ProfileMutationOrigin): Boolean =
    when (this) {
        is SelectorSelectIntent, is StandaloneAwgSelectIntent -> true
        is WarpUpsertIntent -> activate && origin == ProfileMutationOrigin.ExplicitActivation
        else -> false
    }

/** Utility references cover native relay/Xray catalogs and selector members, not WARP/AWG provisioning stores. */
internal fun ProfileMutationIntent.affectsUtilityCatalog(): Boolean =
    when (family) {
        ProfileMutationFamily.Relay, ProfileMutationFamily.Xray, ProfileMutationFamily.Backup,
        ProfileMutationFamily.Subscription,
        -> true

        ProfileMutationFamily.Warp, ProfileMutationFamily.Awg -> false
    }
