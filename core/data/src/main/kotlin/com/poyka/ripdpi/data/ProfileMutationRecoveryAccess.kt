package com.poyka.ripdpi.data

/** Recovery and consistent reads share the catalog's single journal and mutation authority. */
interface ProfileMutationRecoveryAccess : PauseMutationPreparationSource {
    suspend fun recover()

    suspend fun <T> readRecovered(block: suspend () -> T): T

    suspend fun runReset(block: suspend (DurableCommandReceipt) -> Unit): DurableCommandReceipt
}
