package com.poyka.ripdpi.data

/** Runtime-state mutators share the catalog's mandatory recovery boundary without depending on the service layer. */
interface PauseMutationPreparationSource {
    suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation

    suspend fun commitMutationIntent(preparation: ProfileMutationPreparation): ProfileMutationOutcome

    /** Journaled standalone deactivation and one selector-member commit use the original preparation. */
    suspend fun activateSelector(
        preparation: ProfileMutationPreparation,
        groupId: String,
        memberId: String,
        choice: com.poyka.ripdpi.data.selector.SelectorChoicePersistence,
    ): ProfileMutationOutcome

    /** Holds the same recovery mutex through catalog write and checked publication. */
    suspend fun <T> mutateCatalog(
        preparation: ProfileMutationPreparation,
        block: suspend () -> T,
    ): T

    /** A composite Reset may supply its already checked receipt without recursively acquiring recovery. */
    suspend fun <T> mutateReservedCatalog(
        receipt: DurableCommandReceipt,
        block: suspend () -> T,
    ): T
}
