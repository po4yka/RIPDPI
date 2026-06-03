package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsProfileDao {
    @Query("SELECT * FROM diagnostic_profiles ORDER BY updatedAt DESC")
    fun observeProfiles(): Flow<List<DiagnosticProfileEntity>>

    @Query("SELECT * FROM diagnostic_profiles WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): DiagnosticProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: DiagnosticProfileEntity)

    @Upsert
    suspend fun upsertPackVersion(version: TargetPackVersionEntity)

    @Query("SELECT * FROM target_pack_versions WHERE packId = :packId LIMIT 1")
    suspend fun getPackVersion(packId: String): TargetPackVersionEntity?
}
