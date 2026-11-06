package com.poyka.ripdpi.data

import kotlinx.coroutines.sync.withLock

/** Captured before-images and compensation remain owned by the original activation command. */
internal class StandaloneAwgMutations(
    private val runtime: ProfileMutationRuntime,
) : StandaloneAwgMutationCoordinator {
    private val pauseAuthority get() = runtime.pauseAuthority
    private val mutex get() = runtime.mutex
    private val afterImageWriter get() = runtime.afterImageWriter
    private val standaloneBefore = mutableMapOf<String, StandaloneSelectionBeforeImage>()

    override suspend fun activateStandaloneAwg(
        preparation: ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome =
        mutex.withLock {
            runtime.recover()
            require(preparation.origin == ProfileMutationOrigin.ExplicitActivation)
            require(profileId.isNotBlank())
            val previous =
                runtime.database.read { rows ->
                    StandaloneSelectionBeforeImage(
                        rows.selection.awg(),
                        rows.selection.xray(),
                        rows.selection.activeGroup(),
                        rows.warpProfiles.activeProfileId(),
                        rows.settings.snapshot().warpEnabled,
                    )
                }
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
            runtime.recover()
            val previous = standaloneBefore[receipt.commandId] ?: return@withLock false
            if (!pauseAuthority.isCurrent(receipt) ||
                runtime.database.read { it.selection.awg() } != expectedProfileId
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
            runtime.recover()
            if (!pauseAuthority.isCurrent(receipt) ||
                runtime.database.read { it.selection.awg() } != expectedProfileId
            ) {
                return@withLock false
            }
            afterImageWriter.executeOwnedMetadata(
                receipt,
                StandaloneAwgClearIntent(receipt.authority, receipt.commandId, expectedProfileId),
            )
        }
}
