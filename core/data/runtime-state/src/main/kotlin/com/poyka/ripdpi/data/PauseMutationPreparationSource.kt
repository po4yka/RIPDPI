package com.poyka.ripdpi.data

/** Runtime-state mutators share the catalog's mandatory recovery boundary without depending on the service layer. */
interface PauseMutationPreparationSource {
    suspend fun captureMutation(origin: ProfileMutationOrigin): ProfileMutationPreparation

    suspend fun commitMutationIntent(preparation: ProfileMutationPreparation): ProfileMutationOutcome
}
