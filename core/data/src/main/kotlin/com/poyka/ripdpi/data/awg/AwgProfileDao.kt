package com.poyka.ripdpi.data.awg

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for the standalone AmneziaWG [AwgProfileEntity] store.
 *
 * Mirrors `DiagnosticsProfileDao`: an observe-all [Flow], a single-row lookup,
 * and an [Upsert] keyed by the stable String primary key so re-saving a profile
 * under its existing [AwgProfileEntity.id] updates the row in place rather than
 * minting a duplicate.
 */
@Dao
interface AwgProfileDao {
    @Query("SELECT * FROM awg_profiles ORDER BY updatedAt DESC")
    fun observeProfiles(): Flow<List<AwgProfileEntity>>

    @Query("SELECT * FROM awg_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): AwgProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: AwgProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: AwgProfileEntity)
}
