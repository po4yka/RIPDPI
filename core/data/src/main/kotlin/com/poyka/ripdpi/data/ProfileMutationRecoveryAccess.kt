package com.poyka.ripdpi.data

/** Migration and consistent reads share the profile database and mutation authority. */
interface ProfileMutationRecoveryAccess : PauseMutationPreparationSource {
    suspend fun recover()

    suspend fun <T> readRecovered(block: suspend () -> T): T

    suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt
}
