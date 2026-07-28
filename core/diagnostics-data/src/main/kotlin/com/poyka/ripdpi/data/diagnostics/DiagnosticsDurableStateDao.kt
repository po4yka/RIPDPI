package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DiagnosticsDurableStateDao {
    @Query("SELECT * FROM diagnostics_durable_state WHERE `key` = :key LIMIT 1")
    suspend fun getDiagnosticsDurableState(key: String): DiagnosticsDurableStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiagnosticsDurableState(state: DiagnosticsDurableStateEntity)

    @Query("DELETE FROM diagnostics_durable_state WHERE `key` = :key")
    suspend fun clearDiagnosticsDurableState(key: String)

    @Query("DELETE FROM diagnostics_durable_state WHERE `key` = :key AND value = :expectedValue")
    suspend fun clearDiagnosticsDurableState(
        key: String,
        expectedValue: String,
    )

    @Query("DELETE FROM diagnostics_durable_state")
    suspend fun clearAllDiagnosticsDurableState()
}
