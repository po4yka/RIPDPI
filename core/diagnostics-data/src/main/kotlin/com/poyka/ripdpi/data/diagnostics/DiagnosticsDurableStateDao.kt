package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DiagnosticsDurableStateDao {
    @Query("SELECT * FROM diagnostics_durable_state WHERE `key` = :key LIMIT 1")
    suspend fun getDiagnosticsDurableState(key: String): DiagnosticsDurableStateEntity?

    @Query(
        """
        SELECT * FROM diagnostics_durable_state
        WHERE `key` LIKE :keyPrefix || '%'
        ORDER BY updatedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getDiagnosticsDurableStateByPrefix(
        keyPrefix: String,
        limit: Int,
    ): List<DiagnosticsDurableStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiagnosticsDurableState(state: DiagnosticsDurableStateEntity)

    @Query("DELETE FROM diagnostics_durable_state WHERE `key` = :key")
    suspend fun clearDiagnosticsDurableState(key: String)

    @Query("DELETE FROM diagnostics_durable_state WHERE `key` = :key AND value = :expectedValue")
    suspend fun clearDiagnosticsDurableState(
        key: String,
        expectedValue: String,
    )

    @Query(
        """
        UPDATE diagnostics_durable_state
        SET value = :replacementValue, updatedAt = :updatedAt
        WHERE `key` = :key AND value = :expectedValue
        """,
    )
    suspend fun replaceDiagnosticsDurableStateIfCurrent(
        key: String,
        expectedValue: String,
        replacementValue: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM diagnostics_durable_state WHERE `key` LIKE :keyPrefix || '%'")
    suspend fun clearDiagnosticsDurableStateByPrefix(keyPrefix: String)

    @Query("DELETE FROM diagnostics_durable_state")
    suspend fun clearAllDiagnosticsDurableState()
}
