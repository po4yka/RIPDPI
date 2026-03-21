package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Named
import javax.inject.Singleton

interface DiagnosticsBootstrapper {
    suspend fun initialize()
}

interface DiagnosticsTimelineSource {
    val activeScanProgress: StateFlow<ScanProgress?>
    val profiles: Flow<List<DiagnosticProfileEntity>>
    val sessions: Flow<List<ScanSessionEntity>>
    val approachStats: Flow<List<BypassApproachSummary>>
    val snapshots: Flow<List<NetworkSnapshotEntity>>
    val contexts: Flow<List<DiagnosticContextEntity>>
    val telemetry: Flow<List<TelemetrySampleEntity>>
    val nativeEvents: Flow<List<NativeSessionEventEntity>>
    val exports: Flow<List<ExportRecordEntity>>
}

interface DiagnosticsScanController {
    suspend fun startScan(pathMode: ScanPathMode): String

    suspend fun cancelActiveScan()

    suspend fun setActiveProfile(profileId: String)
}

interface DiagnosticsDetailLoader {
    suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail

    suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail
}

interface DiagnosticsShareService {
    suspend fun buildShareSummary(sessionId: String?): ShareSummary

    suspend fun createArchive(sessionId: String?): DiagnosticsArchive
}

interface DiagnosticsResolverActions {
    suspend fun keepResolverRecommendationForSession(sessionId: String)

    suspend fun saveResolverRecommendation(sessionId: String)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsManagerModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsBootstrapper(
        bootstrapper: DefaultDiagnosticsBootstrapper,
    ): DiagnosticsBootstrapper

    @Binds
    @Singleton
    abstract fun bindDiagnosticsTimelineSource(
        source: DefaultDiagnosticsTimelineSource,
    ): DiagnosticsTimelineSource

    @Binds
    @Singleton
    abstract fun bindDiagnosticsScanController(
        controller: DefaultDiagnosticsScanController,
    ): DiagnosticsScanController

    @Binds
    @Singleton
    abstract fun bindAutomaticProbeLauncher(
        controller: DefaultDiagnosticsScanController,
    ): AutomaticProbeLauncher

    @Binds
    @Singleton
    abstract fun bindDiagnosticsDetailLoader(
        loader: DefaultDiagnosticsDetailLoader,
    ): DiagnosticsDetailLoader

    @Binds
    @Singleton
    abstract fun bindDiagnosticsShareService(
        service: DefaultDiagnosticsShareService,
    ): DiagnosticsShareService

    @Binds
    @Singleton
    abstract fun bindDiagnosticsResolverActions(
        actions: DefaultDiagnosticsResolverActions,
    ): DiagnosticsResolverActions

    @Binds
    @Singleton
    abstract fun bindDiagnosticsArchiveExporter(
        exporter: DefaultDiagnosticsArchiveExporter,
    ): DiagnosticsArchiveExporter

    companion object {
        @Provides
        @Singleton
        @Named("diagnosticsJson")
        fun provideDiagnosticsJson(): Json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                encodeDefaults = true
                explicitNulls = false
            }

        @Provides
        @Singleton
        fun provideDiagnosticsArchiveClock(): DiagnosticsArchiveClock = DiagnosticsArchiveClock { System.currentTimeMillis() }

        @Provides
        @Singleton
        fun provideDiagnosticsArchiveIdGenerator(): DiagnosticsArchiveIdGenerator =
            DiagnosticsArchiveIdGenerator { UUID.randomUUID().toString() }

        @Provides
        @Singleton
        fun provideDiagnosticsArchiveFileStore(
            @ApplicationContext context: Context,
            clock: DiagnosticsArchiveClock,
        ): DiagnosticsArchiveFileStore = DiagnosticsArchiveFileStore(cacheDir = context.cacheDir, clock = clock)

        @Provides
        @Named("automaticHandoverProbeDelayMs")
        fun provideAutomaticHandoverProbeDelayMs(): Long = 15_000L

        @Provides
        @Named("automaticHandoverProbeCooldownMs")
        fun provideAutomaticHandoverProbeCooldownMs(): Long = 24L * 60L * 60L * 1_000L

        @Provides
        @Named("importBundledProfilesOnInitialize")
        fun provideImportBundledProfilesOnInitialize(): Boolean = true
    }
}
