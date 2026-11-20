package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.poyka.ripdpi.data.RememberedNetworkPolicyMaxValidatedAgeMs
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

    @Query("SELECT * FROM remembered_network_policies WHERE id = :id LIMIT 1")
    suspend fun getRememberedNetworkPolicyById(id: Long): RememberedNetworkPolicyEntity?

    // A suppressed policy stays out until a probe validates it again; suppressedUntil is not consulted.
    @Query(
        """
        SELECT * FROM remembered_network_policies
        WHERE fingerprintHash = :fingerprintHash
            AND mode = :mode
            AND status = :validatedStatus
            AND lastValidatedAt >= :now - $RememberedNetworkPolicyMaxValidatedAgeMs
        ORDER BY lastValidatedAt DESC, updatedAt DESC
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
        WHERE NOT EXISTS (
            SELECT 1 FROM diagnostics_durable_state AS dependency
            WHERE substr(dependency.`key`, 1, length('runtime_terminal_policy:')) =
                    'runtime_terminal_policy:'
                AND dependency.value = CAST(remembered_network_policies.id AS TEXT)
        )
        AND id NOT IN (
            SELECT policy.id FROM remembered_network_policies AS policy
            WHERE NOT EXISTS (
                SELECT 1 FROM diagnostics_durable_state AS dependency
                WHERE substr(dependency.`key`, 1, length('runtime_terminal_policy:')) =
                        'runtime_terminal_policy:'
                    AND dependency.value = CAST(policy.id AS TEXT)
            )
            ORDER BY policy.updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimRememberedNetworkPoliciesToCount(retainCount: Int)
}
