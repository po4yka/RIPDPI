package com.poyka.ripdpi.data.awg

import kotlinx.coroutines.flow.Flow

/** Metadata and its credential result come from the same storage snapshot. */
data class AwgStoredRecord(
    val profile: AwgProfileEntity,
    val secrets: Result<AwgSecrets?>,
)

interface AwgProfileReadStore {
    suspend fun load(id: String): AwgStoredRecord?

    suspend fun list(): List<AwgStoredRecord>

    fun observe(): Flow<List<AwgStoredRecord>>
}
