package com.poyka.ripdpi.data.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsTelemetryDao {
    @Query("SELECT * FROM telemetry_samples ORDER BY createdAt DESC LIMIT :limit")
    fun observeTelemetry(limit: Int = 200): Flow<List<TelemetrySampleEntity>>

    @Query(
        """
        SELECT * FROM telemetry_samples
        WHERE activeMode = :activeMode
            AND telemetryNetworkFingerprintHash = :fingerprintHash
            AND createdAt >= :createdAfter
        ORDER BY createdAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestTelemetrySampleForFingerprint(
        activeMode: String,
        fingerprintHash: String,
        createdAfter: Long,
    ): TelemetrySampleEntity?

    @Query(
        """
        SELECT * FROM telemetry_samples
        WHERE createdAt >= :startedAt
            AND createdAt <= :finishedAt
            AND (
                sessionId = :sessionId
                OR connectionSessionId IN (:connectionSessionIds)
            )
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getTelemetryForArchiveStage(
        sessionId: String,
        connectionSessionIds: List<String>,
        startedAt: Long,
        finishedAt: Long,
        limit: Int,
    ): List<TelemetrySampleEntity>

    @Query(
        """
        SELECT * FROM telemetry_samples
        WHERE connectionSessionId = :connectionSessionId
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    fun observeTelemetryForConnectionSession(
        connectionSessionId: String,
        limit: Int = 200,
    ): Flow<List<TelemetrySampleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetrySample(sample: TelemetrySampleEntity)

    @Query("DELETE FROM telemetry_samples")
    suspend fun deleteAllTelemetrySamples()
}
