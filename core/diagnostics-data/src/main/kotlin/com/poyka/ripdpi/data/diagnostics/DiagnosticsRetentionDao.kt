package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Query

// A Room DAO with one retention-delete function per table is cohesive; splitting it would be artificial.
@Suppress("TooManyFunctions")
@Dao
interface DiagnosticsRetentionDao {
    @Query("DELETE FROM network_snapshots WHERE capturedAt < :threshold")
    suspend fun deleteSnapshotsOlderThan(threshold: Long)

    @Query("DELETE FROM diagnostic_context_snapshots WHERE capturedAt < :threshold")
    suspend fun deleteContextOlderThan(threshold: Long)

    @Query(
        """
        DELETE FROM telemetry_samples
        WHERE createdAt < :threshold
            AND NOT EXISTS (
                SELECT 1 FROM diagnostics_durable_state AS outbox
                WHERE outbox.`key` = 'runtime_terminal_outbox:' || telemetry_samples.connectionSessionId
            )
        """,
    )
    suspend fun deleteTelemetryOlderThan(threshold: Long)

    @Query(
        """
        DELETE FROM native_session_events
        WHERE createdAt < :threshold
            AND NOT EXISTS (
                SELECT 1 FROM diagnostics_durable_state AS outbox
                WHERE outbox.`key` = 'runtime_terminal_outbox:' || native_session_events.connectionSessionId
            )
        """,
    )
    suspend fun deleteNativeEventsOlderThan(threshold: Long)

    @Query("DELETE FROM export_records WHERE createdAt < :threshold")
    suspend fun deleteExportRecordsOlderThan(threshold: Long)

    @Query(
        """
        DELETE FROM bypass_usage_sessions
        WHERE finishedAt IS NOT NULL
            AND finishedAt < :threshold
            AND NOT EXISTS (
                SELECT 1 FROM diagnostics_durable_state AS outbox
                WHERE outbox.`key` = 'runtime_terminal_outbox:' || bypass_usage_sessions.id
            )
        """,
    )
    suspend fun deleteBypassUsageSessionsOlderThan(threshold: Long)

    @Query("DELETE FROM remembered_network_policies WHERE updatedAt < :threshold")
    suspend fun deleteRememberedNetworkPoliciesOlderThan(threshold: Long)

    @Query("DELETE FROM network_dns_path_preferences WHERE updatedAt < :threshold")
    suspend fun deleteNetworkDnsPathPreferencesOlderThan(threshold: Long)

    @Query("DELETE FROM network_dns_blocked_paths WHERE updatedAt < :threshold")
    suspend fun deleteBlockedPathsOlderThan(threshold: Long)

    @Query("DELETE FROM network_edge_preferences WHERE updatedAt < :threshold")
    suspend fun deleteNetworkEdgePreferencesOlderThan(threshold: Long)

    @Query("DELETE FROM probe_results WHERE createdAt < :threshold")
    suspend fun deleteProbeResultsOlderThan(threshold: Long)

    @Query("DELETE FROM scan_sessions WHERE finishedAt IS NOT NULL AND finishedAt < :threshold")
    suspend fun deleteScanSessionsOlderThan(threshold: Long)
}
