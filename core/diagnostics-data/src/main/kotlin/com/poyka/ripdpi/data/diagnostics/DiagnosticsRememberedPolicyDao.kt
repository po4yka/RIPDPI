package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsRememberedPolicyDao {
    @Query("SELECT * FROM remembered_network_policies ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRememberedNetworkPolicies(limit: Int = 64): Flow<List<RememberedNetworkPolicyEntity>>

    @Query(
        """
        SELECT * FROM remembered_network_policies
        WHERE fingerprintHash = :fingerprintHash AND mode = :mode
        LIMIT 1
        """,
    )
    suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity?

    @Query(
        """
        SELECT * FROM remembered_network_policies
        WHERE fingerprintHash = :fingerprintHash
            AND mode = :mode
            AND status = :validatedStatus
            AND (suppressedUntil IS NULL OR suppressedUntil <= :now)
        ORDER BY COALESCE(lastValidatedAt, 0) DESC, updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
        validatedStatus: String,
        now: Long,
    ): RememberedNetworkPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long

    @Query("DELETE FROM remembered_network_policies")
    suspend fun clearRememberedNetworkPolicies()

    @Query("DELETE FROM remembered_network_policies WHERE id = :id")
    suspend fun deleteRememberedNetworkPolicyById(id: Long)

    @Query("SELECT COUNT(*) FROM remembered_network_policies WHERE fingerprintHash = :fingerprintHash")
    suspend fun countRememberedNetworkPoliciesForFingerprint(fingerprintHash: String): Int

    @Query(
        """
        DELETE FROM remembered_network_policies
        WHERE id NOT IN (
            SELECT policy.id FROM remembered_network_policies AS policy
            ORDER BY CASE WHEN EXISTS (
                SELECT 1 FROM diagnostics_durable_state AS dependency
                WHERE dependency.`key` LIKE 'runtime_terminal_policy:%'
                    AND dependency.value = CAST(policy.id AS TEXT)
            ) THEN 1 ELSE 0 END DESC,
            policy.updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimRememberedNetworkPoliciesToCount(retainCount: Int)
}
