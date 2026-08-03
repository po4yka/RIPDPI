package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsScanDao {
    // reportJson is projected via CASE (see DiagnosticsReportPersister.MaxInlineReportJsonBytes)
    // to null payloads above ~1.5MB; larger rows overflow Android's 2MB CursorWindow.
    @Query(
        """
        SELECT id, profileId, approachProfileId, approachProfileName, strategyId, strategyLabel, strategyJson,
               pathMode, serviceMode, status, summary,
               CASE WHEN length(CAST(reportJson AS BLOB)) <= 1500000 THEN reportJson ELSE NULL END AS reportJson,
               reportCompletionKind, reportTerminationReason,
               startedAt, finishedAt, launchOrigin, triggerType, triggerClassification, triggerOccurredAt,
               triggerPreviousFingerprintHash, triggerCurrentFingerprintHash
        FROM scan_sessions ORDER BY startedAt DESC LIMIT :limit
        """,
    )
    fun observeRecentScanSessions(limit: Int = 50): Flow<List<ScanSessionEntity>>

    @Query(
        """
        SELECT id, profileId, approachProfileId, approachProfileName, strategyId, strategyLabel, strategyJson,
               pathMode, serviceMode, status, summary,
               CASE WHEN length(CAST(reportJson AS BLOB)) <= 1500000 THEN reportJson ELSE NULL END AS reportJson,
               reportCompletionKind, reportTerminationReason,
               startedAt, finishedAt, launchOrigin, triggerType, triggerClassification, triggerOccurredAt,
               triggerPreviousFingerprintHash, triggerCurrentFingerprintHash
        FROM scan_sessions WHERE id = :sessionId LIMIT 1
        """,
    )
    suspend fun getScanSession(sessionId: String): ScanSessionEntity?

    @Upsert
    suspend fun upsertScanSession(session: ScanSessionEntity)

    @Query("SELECT * FROM probe_results WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity>

    @Upsert
    suspend fun insertProbeResults(results: List<ProbeResultEntity>)

    @Query("DELETE FROM probe_results WHERE sessionId = :sessionId")
    suspend fun deleteProbeResultsForSession(sessionId: String)

    @Query("DELETE FROM probe_results")
    suspend fun deleteAllProbeResults()

    @Query("DELETE FROM scan_sessions")
    suspend fun deleteAllScanSessions()
}
