package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsNativeEventDao {
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity)

    @Query("DELETE FROM native_session_events WHERE id = :eventId")
    suspend fun deleteNativeSessionEvent(eventId: String)

    @Query("DELETE FROM native_session_events")
    suspend fun deleteAllNativeEvents()
}
