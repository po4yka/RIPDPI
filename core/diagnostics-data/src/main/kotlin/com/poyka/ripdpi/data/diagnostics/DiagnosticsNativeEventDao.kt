package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsNativeEventDao :
    DiagnosticsNativeEventReadDao,
    DiagnosticsRelayAttemptReadDao,
    DiagnosticsNativeEventArchiveDao,
    DiagnosticsNativeEventWriteDao

interface DiagnosticsNativeEventReadDao {
    @Query("SELECT * FROM native_session_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeNativeEvents(limit: Int = 250): Flow<List<NativeSessionEventEntity>>

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE sessionId IS NULL
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getGlobalNativeEvents(limit: Int = 250): List<NativeSessionEventEntity>

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE sessionId = :sessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getNativeEventsForSession(
        sessionId: String,
        limit: Int = 500,
    ): List<NativeSessionEventEntity>

    @Query("SELECT * FROM native_session_events WHERE id = :id LIMIT 1")
    suspend fun getNativeEventById(id: String): NativeSessionEventEntity?

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeNativeEventsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 250,
    ): Flow<List<NativeSessionEventEntity>>

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE connectionSessionId = :connectionSessionId
          AND (subsystem IS NULL OR subsystem != 'network_transition')
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeRootCauseEventsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 250,
    ): Flow<List<NativeSessionEventEntity>>

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE connectionSessionId = :connectionSessionId
          AND subsystem = 'network_transition'
        ORDER BY createdAt DESC
        """,
    )
    fun observeNetworkTransitionEventsForConnectionSession(
        connectionSessionId: String,
    ): Flow<List<NativeSessionEventEntity>>

    @Query("SELECT * FROM native_session_events WHERE id = :eventId LIMIT 1")
    suspend fun getNativeSessionEvent(eventId: String): NativeSessionEventEntity?

    @Query(
        """
        SELECT * FROM native_session_events
        WHERE subsystem = 'runtime_terminal_outbox'
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getPendingTerminalOutboxes(limit: Int): List<NativeSessionEventEntity>

    @Query(
        """
        SELECT COUNT(*) FROM native_session_events
        WHERE subsystem = 'runtime_terminal_outbox'
        """,
    )
    suspend fun countPendingTerminalOutboxes(): Int
}

interface DiagnosticsRelayAttemptReadDao {
    @Query(
        """
        SELECT * FROM native_session_events
        WHERE connectionSessionId = :connectionSessionId
          AND runtimeId = :runtimeId
          AND attemptId = :attemptId
          AND attemptSequence IS NOT NULL
          AND subsystem = 'relay'
        ORDER BY attemptSequence ASC, createdAt ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun getRelayAttemptTraceEvents(
        connectionSessionId: String,
        runtimeId: String,
        attemptId: Long,
        limit: Int,
    ): List<NativeSessionEventEntity>
}

interface DiagnosticsNativeEventArchiveDao {
    @Query(NativeEventArchiveClassCountsForSessionQuery)
    suspend fun countNativeEventArchiveClassesForSession(
        sessionId: String,
    ): List<DiagnosticsNativeEventArchiveClassCountRow>

    @Query(NativeEventsForSessionByArchiveClassQuery)
    suspend fun getNativeEventsForSessionByArchiveClass(
        sessionId: String,
        archiveClass: String,
        limit: Int,
    ): List<NativeSessionEventEntity>

    @Query(GlobalNativeEventArchiveClassCountsQuery)
    suspend fun countGlobalNativeEventArchiveClasses(): List<DiagnosticsNativeEventArchiveClassCountRow>

    @Query(GlobalNativeEventsByArchiveClassQuery)
    suspend fun getGlobalNativeEventsByArchiveClass(
        archiveClass: String,
        limit: Int,
    ): List<NativeSessionEventEntity>
}

interface DiagnosticsNativeEventWriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity)

    @Query("DELETE FROM native_session_events WHERE id = :eventId")
    suspend fun deleteNativeSessionEvent(eventId: String)

    @Query("DELETE FROM native_session_events")
    suspend fun deleteAllNativeEvents()
}

private const val NativeEventArchiveClassSql =
    """
    CASE
        WHEN instr(lower(message), 'event_kind=data_plane_final') > 0
            OR instr(lower(message), 'event_kind=terminal_report') > 0
            OR instr(lower(message), 'final_report=') > 0
            OR lower(coalesce(subsystem, '')) IN ('runtime_terminal', 'runtime_terminal_outbox', 'data_plane_final')
            THEN 'terminal'
        WHEN lower(coalesce(subsystem, '')) = 'post_runtime_restore'
            OR lower(coalesce(stage, '')) = 'post_runtime_restore'
            OR instr(lower(message), 'event_kind=post_runtime_restore') > 0
            OR instr(lower(message), 'event_kind=runtime_restoration') > 0
            THEN 'runtime_restoration'
        WHEN cleanupReceipt IS NOT NULL
            OR lower(coalesce(subsystem, '')) IN ('candidate_cleanup', 'runtime_cleanup')
            OR lower(coalesce(stage, '')) IN ('candidate_cleanup', 'runtime_cleanup')
            OR instr(lower(message), 'forced_abort=') > 0
            OR instr(lower(message), 'cleanup_receipt=') > 0
            OR instr(lower(message), 'joined=') > 0
            OR instr(lower(message), 'stopped=') > 0
            THEN 'cleanup'
        WHEN lower(coalesce(subsystem, '')) IN ('strategy_candidate', 'candidate_trial')
            OR lower(coalesce(stage, '')) IN ('strategy_candidate', 'candidate_trial', 'candidate')
            THEN 'candidate_trial'
        ELSE 'other'
    END
    """

private const val NativeEventArchiveClassCountsForSessionQuery =
    """
    SELECT archiveClass, COUNT(*) AS eventCount
    FROM (
        SELECT
    """ +
        NativeEventArchiveClassSql +
        """
            AS archiveClass
        FROM native_session_events
        WHERE sessionId = :sessionId
    )
    GROUP BY archiveClass
    """

private const val GlobalNativeEventArchiveClassCountsQuery =
    """
    SELECT archiveClass, COUNT(*) AS eventCount
    FROM (
        SELECT
    """ +
        NativeEventArchiveClassSql +
        """
            AS archiveClass
        FROM native_session_events
        WHERE sessionId IS NULL
    )
    GROUP BY archiveClass
    """

private const val NativeEventsForSessionByArchiveClassQuery =
    """
    SELECT * FROM native_session_events
    WHERE sessionId = :sessionId
      AND (
    """ +
        NativeEventArchiveClassSql +
        """
      ) = :archiveClass
    ORDER BY createdAt DESC, id DESC
    LIMIT :limit
    """

private const val GlobalNativeEventsByArchiveClassQuery =
    """
    SELECT * FROM native_session_events
    WHERE sessionId IS NULL
      AND (
    """ +
        NativeEventArchiveClassSql +
        """
      ) = :archiveClass
    ORDER BY createdAt DESC, id DESC
    LIMIT :limit
    """
