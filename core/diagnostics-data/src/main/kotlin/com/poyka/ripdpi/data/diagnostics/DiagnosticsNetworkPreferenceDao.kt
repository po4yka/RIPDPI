package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// A Room DAO with one function per query is cohesive; splitting it would be artificial.
@Suppress("TooManyFunctions")
@Dao
interface DiagnosticsNetworkPreferenceDao {
    @Query(
        """
        SELECT * FROM network_dns_path_preferences
        WHERE fingerprintHash = :fingerprintHash
        LIMIT 1
        """,
    )
    suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkDnsPathPreference(preference: NetworkDnsPathPreferenceEntity): Long

    @Query("DELETE FROM network_dns_path_preferences")
    suspend fun clearNetworkDnsPathPreferences()

    @Query("DELETE FROM network_dns_path_preferences WHERE fingerprintHash = :fingerprintHash")
    suspend fun deleteNetworkDnsPathPreferencesForFingerprint(fingerprintHash: String)

    @Query(
        """
        DELETE FROM network_dns_path_preferences
        WHERE id NOT IN (
            SELECT id FROM network_dns_path_preferences
            ORDER BY updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimNetworkDnsPathPreferencesToCount(retainCount: Int)

    @Query("SELECT pathKey FROM network_dns_blocked_paths WHERE fingerprintHash = :fingerprintHash")
    suspend fun getBlockedPathKeys(fingerprintHash: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlockedPath(entity: NetworkDnsBlockedPathEntity): Long

    @Query("DELETE FROM network_dns_blocked_paths")
    suspend fun clearBlockedPaths()

    @Query("DELETE FROM network_dns_blocked_paths WHERE fingerprintHash = :fingerprintHash")
    suspend fun deleteBlockedPathsForFingerprint(fingerprintHash: String)

    @Query(
        """
        DELETE FROM network_dns_blocked_paths
        WHERE id NOT IN (
            SELECT id FROM network_dns_blocked_paths
            ORDER BY updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimBlockedPathsToCount(retainCount: Int)

    @Query(
        """
        SELECT * FROM network_edge_preferences
        WHERE fingerprintHash = :fingerprintHash AND host = :host AND transportKind = :transportKind
        LIMIT 1
        """,
    )
    suspend fun getNetworkEdgePreference(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): NetworkEdgePreferenceEntity?

    @Query(
        """
        SELECT * FROM network_edge_preferences
        WHERE fingerprintHash = :fingerprintHash
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun getNetworkEdgePreferencesForFingerprint(fingerprintHash: String): List<NetworkEdgePreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkEdgePreference(preference: NetworkEdgePreferenceEntity): Long

    @Query("DELETE FROM network_edge_preferences")
    suspend fun clearNetworkEdgePreferences()

    @Query("DELETE FROM network_edge_preferences WHERE fingerprintHash = :fingerprintHash")
    suspend fun deleteNetworkEdgePreferencesForFingerprint(fingerprintHash: String)

    @Query(
        """
        DELETE FROM network_edge_preferences
        WHERE id NOT IN (
            SELECT id FROM network_edge_preferences
            ORDER BY updatedAt DESC
            LIMIT :retainCount
        )
        """,
    )
    suspend fun trimNetworkEdgePreferencesToCount(retainCount: Int)
}
