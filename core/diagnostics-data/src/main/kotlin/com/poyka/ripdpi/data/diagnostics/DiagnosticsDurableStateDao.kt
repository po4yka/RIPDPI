package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsDurableStateDao {
    @Query("SELECT * FROM diagnostics_durable_state WHERE `key` = :key LIMIT 1")
    suspend fun getDiagnosticsDurableState(key: String): DiagnosticsDurableStateEntity?

    @Query(
        """
        SELECT * FROM diagnostics_durable_state
        WHERE substr(`key`, 1, length(:keyPrefix)) = :keyPrefix
        ORDER BY updatedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getDiagnosticsDurableStateByPrefix(
        keyPrefix: String,
        limit: Int,
    ): List<DiagnosticsDurableStateEntity>

    @Query(
        """
        SELECT * FROM diagnostics_durable_state
        WHERE substr(`key`, 1, length(:keyPrefix)) = :keyPrefix
        ORDER BY updatedAt ASC
        """,
    )
    fun observeDiagnosticsDurableStateByPrefix(keyPrefix: String): Flow<List<DiagnosticsDurableStateEntity>>

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

    @Query("DELETE FROM diagnostics_durable_state WHERE substr(`key`, 1, length(:keyPrefix)) = :keyPrefix")
    suspend fun clearDiagnosticsDurableStateByPrefix(keyPrefix: String)

    @Query(
        """
        DELETE FROM diagnostics_durable_state
        WHERE substr(`key`, 1, length(:keyPrefix)) = :keyPrefix AND updatedAt < :minimumUpdatedAt
        """,
    )
    suspend fun clearDiagnosticsDurableStateByPrefixOlderThan(
        keyPrefix: String,
        minimumUpdatedAt: Long,
    )

    @Query(
        """
        DELETE FROM diagnostics_durable_state
        WHERE substr(`key`, 1, length(:keyPrefix)) = :keyPrefix
            AND `key` NOT IN (
                SELECT `key` FROM diagnostics_durable_state
                WHERE substr(`key`, 1, length(:keyPrefix)) = :keyPrefix
                ORDER BY updatedAt DESC, `key` DESC
                LIMIT :retainCount
            )
        """,
    )
    suspend fun trimDiagnosticsDurableStateByPrefixToCount(
        keyPrefix: String,
        retainCount: Int,
    )

    @Query("DELETE FROM diagnostics_durable_state")
    suspend fun clearAllDiagnosticsDurableState()
}
