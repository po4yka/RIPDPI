package com.poyka.ripdpi.data

/** Provider pointer changes are journaled by the profile owner, outside native/service callbacks. */
interface StandaloneAwgMutationCoordinator {
    suspend fun activateStandaloneAwg(
        preparation: ProfileMutationPreparation,
        profileId: String,
    ): ProfileMutationOutcome

    suspend fun compensateStandaloneAwg(
        receipt: ProfileActivationReceipt,
        expectedProfileId: String,
    ): Boolean

    suspend fun clearStandaloneAwg(
        receipt: RuntimeStopReceipt,
        expectedProfileId: String,
    ): Boolean
}
