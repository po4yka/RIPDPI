package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsSnapshotDao {
    @Query("SELECT * FROM network_snapshots ORDER BY capturedAt DESC LIMIT :limit")
    fun observeSnapshots(limit: Int = 100): Flow<List<NetworkSnapshotEntity>>

    @Query(
        """
        SELECT * FROM network_snapshots
        WHERE sessionId = :sessionId
        ORDER BY capturedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getSnapshotsForSession(
        sessionId: String,
        limit: Int = 200,
    ): List<NetworkSnapshotEntity>

    @Query(
        """
        SELECT * FROM network_snapshots
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY capturedAt DESC
        LIMIT :limit
        """,
    )
    fun observeSnapshotsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<NetworkSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkSnapshot(snapshot: NetworkSnapshotEntity)

    @Query("DELETE FROM network_snapshots")
    suspend fun deleteAllSnapshots()

    @Query("SELECT * FROM diagnostic_context_snapshots ORDER BY capturedAt DESC LIMIT :limit")
    fun observeContextSnapshots(limit: Int = 100): Flow<List<DiagnosticContextEntity>>

    @Query(
        """
        SELECT * FROM diagnostic_context_snapshots
        WHERE sessionId = :sessionId
        ORDER BY capturedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getContextsForSession(
        sessionId: String,
        limit: Int = 200,
    ): List<DiagnosticContextEntity>

    @Query(
        """
        SELECT * FROM diagnostic_context_snapshots
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY capturedAt DESC
        LIMIT :limit
        """,
    )
    fun observeContextSnapshotsForConnectionSession(
        connectionSessionId: String,
        limit: Int = 100,
    ): Flow<List<DiagnosticContextEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity)

    @Query("DELETE FROM diagnostic_context_snapshots")
    suspend fun deleteAllContextSnapshots()
}
