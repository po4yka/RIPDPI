package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

internal interface HistoryTimelineDataSource {
    fun observeConnectionSessions(): Flow<List<BypassUsageSessionEntity>>

    fun observeDiagnosticsSessions(): Flow<List<ScanSessionEntity>>

    fun observeNativeEvents(): Flow<List<NativeSessionEventEntity>>
}

internal interface HistoryConnectionDetailSource {
    suspend fun getConnectionSession(sessionId: String): BypassUsageSessionEntity?

    suspend fun getConnectionSnapshots(sessionId: String): List<NetworkSnapshotEntity>

    suspend fun getConnectionContexts(sessionId: String): List<DiagnosticContextEntity>

    suspend fun getConnectionTelemetry(sessionId: String): List<TelemetrySampleEntity>

    suspend fun getConnectionNativeEvents(sessionId: String): List<NativeSessionEventEntity>
}

internal interface HistoryDetailLoader {
    suspend fun loadConnectionDetail(sessionId: String): HistoryConnectionDetailUiModel?

    suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticsSessionDetailUiModel?
}

internal interface HistoryInitializer {
    suspend fun initialize()
}

internal class DefaultHistoryRepositoryDataSource
    @Inject
    constructor(
        private val historyRepository: DiagnosticsHistoryRepository,
    ) : HistoryTimelineDataSource, HistoryConnectionDetailSource {
        override fun observeConnectionSessions(): Flow<List<BypassUsageSessionEntity>> =
            historyRepository.observeBypassUsageSessions(limit = 120)

        override fun observeDiagnosticsSessions(): Flow<List<ScanSessionEntity>> =
            historyRepository.observeRecentScanSessions(limit = 120)

        override fun observeNativeEvents(): Flow<List<NativeSessionEventEntity>> =
            historyRepository.observeNativeEvents(limit = 250)

        override suspend fun getConnectionSession(sessionId: String): BypassUsageSessionEntity? =
            historyRepository.getBypassUsageSession(sessionId)

        override suspend fun getConnectionSnapshots(sessionId: String): List<NetworkSnapshotEntity> =
            historyRepository.observeConnectionSnapshots(sessionId, limit = 40).first()

        override suspend fun getConnectionContexts(sessionId: String): List<DiagnosticContextEntity> =
            historyRepository.observeConnectionContexts(sessionId, limit = 20).first()

        override suspend fun getConnectionTelemetry(sessionId: String): List<TelemetrySampleEntity> =
            historyRepository.observeConnectionTelemetry(sessionId, limit = 60).first()

        override suspend fun getConnectionNativeEvents(sessionId: String): List<NativeSessionEventEntity> =
            historyRepository.observeConnectionNativeEvents(sessionId, limit = 80).first()
    }

internal class DefaultHistoryDetailLoader
    @Inject
    constructor(
        private val connectionDetailSource: HistoryConnectionDetailSource,
        private val diagnosticsManager: DiagnosticsManager,
        private val connectionDetailUiFactory: HistoryConnectionDetailUiFactory,
        private val diagnosticsSessionDetailUiMapper: DiagnosticsSessionDetailUiMapper,
    ) : HistoryDetailLoader {
        override suspend fun loadConnectionDetail(sessionId: String): HistoryConnectionDetailUiModel? {
            val session = connectionDetailSource.getConnectionSession(sessionId) ?: return null
            return connectionDetailUiFactory.toConnectionDetail(
                session = session,
                snapshots = connectionDetailSource.getConnectionSnapshots(sessionId),
                contexts = connectionDetailSource.getConnectionContexts(sessionId),
                telemetry = connectionDetailSource.getConnectionTelemetry(sessionId),
                events = connectionDetailSource.getConnectionNativeEvents(sessionId),
            )
        }

        override suspend fun loadDiagnosticsDetail(sessionId: String): DiagnosticsSessionDetailUiModel? =
            diagnosticsSessionDetailUiMapper.toSessionDetailUiModel(
                detail = diagnosticsManager.loadSessionDetail(sessionId),
                showSensitiveDetails = false,
            )
    }

internal class DefaultHistoryInitializer
    @Inject
    constructor(
        private val diagnosticsManager: DiagnosticsManager,
    ) : HistoryInitializer {
        override suspend fun initialize() {
            diagnosticsManager.initialize()
        }
    }

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class HistoryViewModelModule {
    @Binds
    abstract fun bindHistoryTimelineDataSource(
        dataSource: DefaultHistoryRepositoryDataSource,
    ): HistoryTimelineDataSource

    @Binds
    abstract fun bindHistoryConnectionDetailSource(
        dataSource: DefaultHistoryRepositoryDataSource,
    ): HistoryConnectionDetailSource

    @Binds
    abstract fun bindHistoryDetailLoader(
        loader: DefaultHistoryDetailLoader,
    ): HistoryDetailLoader

    @Binds
    abstract fun bindHistoryInitializer(
        initializer: DefaultHistoryInitializer,
    ): HistoryInitializer

    @Binds
    abstract fun bindDiagnosticsSessionDetailUiMapper(
        factory: DiagnosticsSessionDetailUiFactory,
    ): DiagnosticsSessionDetailUiMapper
}
