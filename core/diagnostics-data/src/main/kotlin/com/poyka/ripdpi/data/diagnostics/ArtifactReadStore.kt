package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.flow.Flow

internal class ArtifactReadStore(
    private val dao: DiagnosticsDao,
) : DiagnosticsArtifactReadStore,
    DiagnosticsArtifactQueryStore {
    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = dao.observeSnapshots(limit)

    override suspend fun getSnapshotsForSession(
        sessionId: String,
        limit: Int,
    ): List<NetworkSnapshotEntity> = dao.getSnapshotsForSession(sessionId = sessionId, limit = limit)

    override fun observeConnectionSnapshots(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NetworkSnapshotEntity>> =
        dao.observeSnapshotsForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> = dao.observeContextSnapshots(limit)

    override suspend fun getContextsForSession(
        sessionId: String,
        limit: Int,
    ): List<DiagnosticContextEntity> = dao.getContextsForSession(sessionId = sessionId, limit = limit)

    override fun observeConnectionContexts(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<DiagnosticContextEntity>> =
        dao.observeContextSnapshotsForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = dao.observeTelemetry(limit)

    override suspend fun getLatestTelemetrySampleForFingerprint(
        activeMode: String,
        fingerprintHash: String,
        createdAfter: Long,
    ): TelemetrySampleEntity? =
        dao.getLatestTelemetrySampleForFingerprint(
            activeMode = activeMode,
            fingerprintHash = fingerprintHash,
            createdAfter = createdAfter,
        )

    override suspend fun getTelemetryForArchiveStage(
        sessionId: String,
        connectionSessionIds: List<String>,
        startedAt: Long,
        finishedAt: Long,
        limit: Int,
    ): List<TelemetrySampleEntity> =
        dao.getTelemetryForArchiveStage(
            sessionId = sessionId,
            connectionSessionIds = connectionSessionIds,
            startedAt = startedAt,
            finishedAt = finishedAt,
            limit = limit,
        )

    override fun observeConnectionTelemetry(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<TelemetrySampleEntity>> =
        dao.observeTelemetryForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = dao.observeNativeEvents(limit)

    override suspend fun getNativeEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<NativeSessionEventEntity> = dao.getNativeEventsForSession(sessionId = sessionId, limit = limit)

    override suspend fun getNativeEventById(id: String): NativeSessionEventEntity? = dao.getNativeEventById(id)

    override suspend fun getGlobalNativeEvents(limit: Int): List<NativeSessionEventEntity> =
        dao.getGlobalNativeEvents(limit)

    override fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        dao.observeNativeEventsForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeConnectionRootCauseEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        dao.observeRootCauseEventsForConnectionSession(
            connectionSessionId = connectionSessionId,
            limit = limit,
        )

    override fun observeConnectionNetworkTransitionEvents(
        connectionSessionId: String,
    ): Flow<List<NativeSessionEventEntity>> =
        dao.observeNetworkTransitionEventsForConnectionSession(connectionSessionId)

    override suspend fun getNativeSessionEvent(eventId: String): NativeSessionEventEntity? =
        dao.getNativeSessionEvent(eventId)

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = dao.observeExportRecords(limit)
}
