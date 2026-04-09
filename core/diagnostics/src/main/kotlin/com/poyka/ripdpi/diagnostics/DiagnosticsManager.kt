package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.Mode
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Named
import javax.inject.Singleton

interface DiagnosticsBootstrapper {
    suspend fun initialize()
}

interface DiagnosticsTimelineSource {
    val activeScanProgress: StateFlow<ScanProgress?>
    val activeConnectionSession: StateFlow<DiagnosticConnectionSession?>
    val profiles: Flow<List<DiagnosticProfile>>
    val sessions: Flow<List<DiagnosticScanSession>>
    val approachStats: Flow<List<BypassApproachSummary>>
    val snapshots: Flow<List<DiagnosticNetworkSnapshot>>
    val contexts: Flow<List<DiagnosticContextSnapshot>>
    val telemetry: Flow<List<DiagnosticTelemetrySample>>
    val nativeEvents: Flow<List<DiagnosticEvent>>
    val liveSnapshots: Flow<List<DiagnosticNetworkSnapshot>>
    val liveContexts: Flow<List<DiagnosticContextSnapshot>>
    val liveTelemetry: Flow<List<DiagnosticTelemetrySample>>
    val liveNativeEvents: Flow<List<DiagnosticEvent>>
    val exports: Flow<List<DiagnosticExportRecord>>
}

interface DiagnosticsHistorySource {
    fun observeConnectionSessions(limit: Int = 120): Flow<List<DiagnosticConnectionSession>>

    fun observeDiagnosticsSessions(limit: Int = 120): Flow<List<DiagnosticScanSession>>

    fun observeNativeEvents(limit: Int = 250): Flow<List<DiagnosticEvent>>

    suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail?
}

interface DiagnosticsRememberedPolicySource {
    fun observePolicies(limit: Int = 64): Flow<List<DiagnosticsRememberedPolicy>>

    suspend fun clearAll()
}

interface DiagnosticsActiveConnectionPolicySource {
    val activePolicies: StateFlow<Map<Mode, DiagnosticActiveConnectionPolicy>>

    fun current(mode: Mode): DiagnosticActiveConnectionPolicy? = activePolicies.value[mode]
}

interface DiagnosticsScanController {
    val hiddenAutomaticProbeActive: StateFlow<Boolean>

    suspend fun startScan(
        pathMode: ScanPathMode,
        selectedProfileId: String? = null,
        skipActiveScanCheck: Boolean = false,
        scanDeadlineMs: Long? = null,
        maxCandidates: Int? = null,
    ): DiagnosticsManualScanStartResult

    suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution

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

    suspend fun createArchive(request: DiagnosticsArchiveRequest): DiagnosticsArchive
}

interface DiagnosticsResolverActions {
    suspend fun keepResolverRecommendationForSession(sessionId: String)

    suspend fun saveResolverRecommendation(sessionId: String)
}

@Serializable
data class DiagnosticsAppliedSetting(
    val label: String,
    val value: String,
)

enum class StrategyAdequacy {
    STRATEGY_APPLIED,
    STRATEGY_RECOMMENDED,
    ALL_CANDIDATES_FAILED,
    DNS_ONLY_APPLIED,
    NO_STRATEGY_PROBE,
}

data class DiagnosticsHomeAuditOutcome(
    val sessionId: String,
    val fingerprintHash: String? = null,
    val actionable: Boolean,
    val headline: String,
    val summary: String,
    val confidenceSummary: String? = null,
    val coverageSummary: String? = null,
    val recommendationSummary: String? = null,
    val appliedSettings: List<DiagnosticsAppliedSetting> = emptyList(),
    val strategyAdequacy: StrategyAdequacy? = null,
)

data class DiagnosticsHomeVerificationOutcome(
    val sessionId: String,
    val success: Boolean,
    val headline: String,
    val summary: String,
    val detail: String? = null,
)

interface DiagnosticsHomeWorkflowService {
    suspend fun currentFingerprintHash(): String?

    suspend fun finalizeHomeAudit(sessionId: String): DiagnosticsHomeAuditOutcome

    suspend fun summarizeVerification(sessionId: String): DiagnosticsHomeVerificationOutcome
}

data class DiagnosticsHomeCompositeRunStarted(
    val runId: String,
)

enum class DiagnosticsHomeCompositeRunStatus {
    RUNNING,
    COMPLETED,
}

enum class DiagnosticsHomeCompositeStageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    UNAVAILABLE,
}

data class DiagnosticsHomeCompositeStageSummary(
    val stageKey: String,
    val stageLabel: String,
    val profileId: String,
    val pathMode: ScanPathMode,
    val sessionId: String? = null,
    val status: DiagnosticsHomeCompositeStageStatus,
    val headline: String,
    val summary: String,
    val recommendationContributor: Boolean = false,
)

data class DiagnosticsHomeCompositeOutcome(
    val runId: String,
    val fingerprintHash: String? = null,
    val actionable: Boolean,
    val headline: String,
    val summary: String,
    val recommendationSummary: String? = null,
    val confidenceSummary: String? = null,
    val coverageSummary: String? = null,
    val appliedSettings: List<DiagnosticsAppliedSetting> = emptyList(),
    val recommendedSessionId: String? = null,
    val stageSummaries: List<DiagnosticsHomeCompositeStageSummary> = emptyList(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val skippedStageCount: Int = 0,
    val bundleSessionIds: List<String> = emptyList(),
)

data class DiagnosticsHomeCompositeProgress(
    val runId: String,
    val fingerprintHash: String? = null,
    val status: DiagnosticsHomeCompositeRunStatus = DiagnosticsHomeCompositeRunStatus.RUNNING,
    val activeStageIndex: Int? = null,
    val activeSessionId: String? = null,
    val stages: List<DiagnosticsHomeCompositeStageSummary> = emptyList(),
    val outcome: DiagnosticsHomeCompositeOutcome? = null,
)

interface DiagnosticsHomeCompositeRunService {
    suspend fun startHomeAnalysis(): DiagnosticsHomeCompositeRunStarted

    suspend fun startQuickAnalysis(): DiagnosticsHomeCompositeRunStarted

    fun observeHomeRun(runId: String): Flow<DiagnosticsHomeCompositeProgress>

    suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome

    suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome?

    suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome?

    suspend fun evictCachedOutcome(fingerprintHash: String)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsManagerModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsBootstrapper(bootstrapper: DefaultDiagnosticsBootstrapper): DiagnosticsBootstrapper

    @Binds
    @Singleton
    abstract fun bindDiagnosticsTimelineSource(source: DefaultDiagnosticsTimelineSource): DiagnosticsTimelineSource

    @Binds
    @Singleton
    internal abstract fun bindDiagnosticsScanController(
        controller: DefaultDiagnosticsScanController,
    ): DiagnosticsScanController

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHistorySource(source: DefaultDiagnosticsHistorySource): DiagnosticsHistorySource

    @Binds
    @Singleton
    abstract fun bindDiagnosticsRememberedPolicySource(
        source: DefaultDiagnosticsRememberedPolicySource,
    ): DiagnosticsRememberedPolicySource

    @Binds
    @Singleton
    abstract fun bindDiagnosticsActiveConnectionPolicySource(
        source: DefaultDiagnosticsActiveConnectionPolicySource,
    ): DiagnosticsActiveConnectionPolicySource

    @Binds
    @Singleton
    internal abstract fun bindAutomaticProbeLauncher(
        controller: DefaultDiagnosticsScanController,
    ): AutomaticProbeLauncher

    @Binds
    @Singleton
    abstract fun bindDiagnosticsDetailLoader(loader: DefaultDiagnosticsDetailLoader): DiagnosticsDetailLoader

    @Binds
    @Singleton
    abstract fun bindDiagnosticsShareService(service: DefaultDiagnosticsShareService): DiagnosticsShareService

    @Binds
    @Singleton
    abstract fun bindDiagnosticsResolverActions(actions: DefaultDiagnosticsResolverActions): DiagnosticsResolverActions

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHomeWorkflowService(
        service: DefaultDiagnosticsHomeWorkflowService,
    ): DiagnosticsHomeWorkflowService

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHomeCompositeRunService(
        service: DefaultDiagnosticsHomeCompositeRunService,
    ): DiagnosticsHomeCompositeRunService

    @Binds
    @Singleton
    abstract fun bindProbeResultCache(cache: DefaultProbeResultCache): ProbeResultCache

    @Binds
    @Singleton
    internal abstract fun bindDiagnosticsArchiveExporter(
        exporter: DefaultDiagnosticsArchiveExporter,
    ): DiagnosticsArchiveExporter

    companion object {
        private const val AutomaticHandoverProbeDelaySeconds = 15L
        private const val AutomaticHandoverProbeCooldownHours = 24L
        private const val AutomaticStrategyFailureProbeCooldownHours = 4L
        private const val ImportBundledProfilesOnInitialize = true
        private const val MillisPerSecond = 1_000L
        private const val MinutesPerHour = 60L
        private const val SecondsPerMinute = 60L

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
        fun provideDiagnosticsArchiveClock(): DiagnosticsArchiveClock =
            DiagnosticsArchiveClock { System.currentTimeMillis() }

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
        fun provideAutomaticHandoverProbeDelayMs(): Long = secondsToMillis(AutomaticHandoverProbeDelaySeconds)

        @Provides
        @Named("automaticHandoverProbeCooldownMs")
        fun provideAutomaticHandoverProbeCooldownMs(): Long = hoursToMillis(AutomaticHandoverProbeCooldownHours)

        @Provides
        @Named("automaticStrategyFailureProbeCooldownMs")
        fun provideAutomaticStrategyFailureProbeCooldownMs(): Long =
            hoursToMillis(AutomaticStrategyFailureProbeCooldownHours)

        @Provides
        @Named("importBundledProfilesOnInitialize")
        fun provideImportBundledProfilesOnInitialize(): Boolean = ImportBundledProfilesOnInitialize

        private fun secondsToMillis(seconds: Long): Long = seconds * MillisPerSecond

        private fun hoursToMillis(hours: Long): Long = secondsToMillis(hours * MinutesPerHour * SecondsPerMinute)
    }
}
