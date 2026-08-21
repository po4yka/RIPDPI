package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface HomeDiagnosticsRunDao {
    @Query("SELECT * FROM home_diagnostics_runs WHERE runId = :runId LIMIT 1")
    suspend fun getHomeDiagnosticsRun(runId: String): HomeDiagnosticsRunEntity?

    @Upsert
    suspend fun upsertHomeDiagnosticsRun(run: HomeDiagnosticsRunEntity)

    @Query("DELETE FROM home_diagnostics_runs WHERE updatedAt < :threshold")
    suspend fun deleteHomeDiagnosticsRunsOlderThan(threshold: Long)

    @Query("DELETE FROM home_diagnostics_runs")
    suspend fun deleteAllHomeDiagnosticsRuns()
}
