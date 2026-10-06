package com.poyka.ripdpi.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Captured before-images and compensation remain owned by the original activation command. */
internal class JournaledStandaloneAwgMutations(
    private val stores: ProfileMutationStores,
    private val pauseAuthority: PauseIntentAuthority,
    private val mutex: Mutex,
    private val journalRecovery: ProfileMutationJournalRecovery,
    private val afterImageWriter: ProfileMutationAfterImageWriter,
) : StandaloneAwgMutationCoordinator {
    private val standaloneBefore = mutableMapOf<String, StandaloneSelectionBeforeImage>()

    override suspend fun activateStandaloneAwg(
        preparation: ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome =
        mutex.withLock {
            journalRecovery.recover()
            require(preparation.origin == ProfileMutationOrigin.ExplicitActivation)
            require(profileId.isNotBlank())
            val previous =
                StandaloneSelectionBeforeImage(
                    stores.bootSession.activeAwgProfileId(),
                    stores.xraySelection.current(),
                    stores.selectorChoice.snapshotActiveGroupId(),
                    stores.warpProfiles.activeProfileId(),
                    stores.settings.snapshot().warpEnabled,
                )
            val outcome = afterImageWriter.executeRecovered(preparation, StandaloneAwgSelectIntent(profileId))
            val receipt = (outcome as? ProfileMutationOutcome.Reserved)?.receipt
            if (receipt != null && pauseAuthority.isCurrent(receipt)) {
                standaloneBefore.clear()
                standaloneBefore[receipt.commandId] = previous
            }
            outcome
        }

    override suspend fun compensateStandaloneAwg(
        receipt: ProfileActivationReceipt,
        expectedProfileId: String,
    ): Boolean =
        mutex.withLock {
            journalRecovery.recover()
            val previous = standaloneBefore[receipt.commandId] ?: return@withLock false
            if (!pauseAuthority.isCurrent(receipt) ||
                stores.bootSession.activeAwgProfileId() != expectedProfileId
            ) {
                return@withLock false
            }
            val written =
                afterImageWriter.executeOwnedMetadata(
                    receipt,
                    StandaloneAwgCompensateIntent(
                        receipt.authority,
                        receipt.commandId,
                        expectedProfileId,
                        previous,
                    ),
                )
            if (written) standaloneBefore.remove(receipt.commandId)
            written
        }

    override suspend fun clearStandaloneAwg(
        receipt: RuntimeStopReceipt,
        expectedProfileId: String,
    ): Boolean =
        mutex.withLock {
            journalRecovery.recover()
            if (!pauseAuthority.isCurrent(receipt) ||
                stores.bootSession.activeAwgProfileId() != expectedProfileId
            ) {
                return@withLock false
            }
            afterImageWriter.executeOwnedMetadata(
                receipt,
                StandaloneAwgClearIntent(receipt.authority, receipt.commandId, expectedProfileId),
            )
        }
}
