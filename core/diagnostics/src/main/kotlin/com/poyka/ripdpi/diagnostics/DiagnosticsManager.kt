package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.TrimmableCache
import com.poyka.ripdpi.diagnostics.application.DefaultDiagnosticsBootstrapper
import com.poyka.ripdpi.diagnostics.application.DefaultDiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.exit.AndroidProcessExitHistorySource
import com.poyka.ripdpi.diagnostics.exit.DefaultLastExitInspector
import com.poyka.ripdpi.diagnostics.exit.DefaultProcessExitRuntimeReconciler
import com.poyka.ripdpi.diagnostics.exit.LastExitInspector
import com.poyka.ripdpi.diagnostics.exit.ProcessExitHistorySource
import com.poyka.ripdpi.diagnostics.exit.ProcessExitRuntimeReconciler
import com.poyka.ripdpi.diagnostics.export.DefaultDiagnosticsArchiveExporter
import com.poyka.ripdpi.diagnostics.export.DefaultDiagnosticsShareService
import com.poyka.ripdpi.diagnostics.memory.DefaultNativeMemoryProbe
import com.poyka.ripdpi.diagnostics.memory.NativeMemoryProbe
import com.poyka.ripdpi.diagnostics.profiling.DefaultMemoryProfilingRegistrar
import com.poyka.ripdpi.diagnostics.profiling.MemoryProfilingRegistrar
import com.poyka.ripdpi.diagnostics.queries.DefaultDiagnosticsDetailLoader
import com.poyka.ripdpi.serialization.RipDpiPrettyContractJson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
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

    /**
     * Removes a single remembered policy entry and truly purges any per-network learned state
     * (DNS path preference, blocked paths, edge preferences) once no other mode still references
     * the same network scope (fingerprint).
     */
    suspend fun deletePolicy(policy: DiagnosticsRememberedPolicy)

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
        allowSensitiveProfileStart: Boolean = false,
        scanDeadlineMs: Long? = null,
        maxCandidates: Int? = null,
        targetOverrides: DiagnosticsScanTargetOverrides? = null,
    ): DiagnosticsManualScanStartResult

    suspend fun startScanOwnedBy(
        ownerId: String,
        pathMode: ScanPathMode,
        selectedProfileId: String? = null,
        skipActiveScanCheck: Boolean = false,
        allowSensitiveProfileStart: Boolean = false,
        scanDeadlineMs: Long? = null,
        maxCandidates: Int? = null,
        targetOverrides: DiagnosticsScanTargetOverrides? = null,
        resumeRuntimeAfterRawPath: Boolean = false,
    ): DiagnosticsManualScanStartResult =
        startScan(
            pathMode = pathMode,
            selectedProfileId = selectedProfileId,
            skipActiveScanCheck = skipActiveScanCheck,
            allowSensitiveProfileStart = allowSensitiveProfileStart,
            scanDeadlineMs = scanDeadlineMs,
            maxCandidates = maxCandidates,
            targetOverrides = targetOverrides,
        )

    suspend fun resolveHiddenProbeConflict(
        requestId: String,
        action: HiddenProbeConflictAction,
    ): DiagnosticsManualScanResolution

    suspend fun cancelActiveScan()

    suspend fun cancelScan(sessionId: String) = cancelActiveScan()

    fun activeSessionIdsOwnedBy(ownerId: String): Set<String> = emptySet()

    suspend fun releaseSessionsOwnedBy(ownerId: String) = Unit

    suspend fun setActiveProfile(profileId: String)
}

data class DiagnosticsScanTargetOverrides(
    val domainTargets: List<DomainTarget>? = null,
    val serviceTargets: List<ServiceTarget>? = null,
    val circumventionTargets: List<CircumventionTarget>? = null,
)

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

    suspend fun writeArchive(
        request: DiagnosticsArchiveRequest,
        destination: OutputStream,
    ): Unit = error("Direct archive export is unavailable")
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
    val capabilityEvidence: List<DiagnosticsCapabilityEvidence> = emptyList(),
    val strategyAdequacy: StrategyAdequacy? = null,
    val directModeVerdict: DirectModeVerdict? = null,
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
    CANCELLED,
    FAILED,
}

class DiagnosticsHomeRunTerminatedException(
    val status: DiagnosticsHomeCompositeRunStatus,
) : IllegalStateException("Home diagnostics run terminated with status $status")

enum class DiagnosticsHomeCompositeStageStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    UNAVAILABLE,
}

enum class DiagnosticsHomeCompositeStageUnavailableReason {
    SERVICE_NOT_RUNNING,
    ACTIVE_VPN_PATH_NOT_OBSERVED,
    PROXY_ENDPOINT_MISMATCH,
    RUNTIME_CHANGED_OR_UNAVAILABLE,
}

data class DiagnosticsHomeCompositeStageSummary(
    val stageKey: String,
    val stageLabel: String,
    val profileId: String,
    val pathMode: ScanPathMode?,
    val evidenceType: HomeCompositeStageEvidenceType =
        when (stageKey) {
            "detection_signals" -> HomeCompositeStageEvidenceType.DETECTION_SIGNALS
            "vpn_route_evidence" -> HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE
            else -> HomeCompositeStageEvidenceType.ACTIVE_SCAN
        },
    val vantage: HomeCompositeStageVantage =
        when {
            evidenceType == HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE -> {
                HomeCompositeStageVantage.VPN_ROUTE_OBSERVATION
            }

            pathMode == ScanPathMode.IN_PATH -> {
                HomeCompositeStageVantage.PROXY_VANTAGE
            }

            else -> {
                HomeCompositeStageVantage.RAW_PATH
            }
        },
    val targetReachability: HomeCompositeTargetReachability =
        if (evidenceType == HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE) {
            HomeCompositeTargetReachability.UNVERIFIED
        } else {
            HomeCompositeTargetReachability.VERIFIED_BY_STAGE
        },
    val sessionId: String? = null,
    val status: DiagnosticsHomeCompositeStageStatus,
    val headline: String,
    val summary: String,
    val unavailableReason: DiagnosticsHomeCompositeStageUnavailableReason? = null,
    val recommendationContributor: Boolean = false,
    /** Wall-clock duration of the underlying scan session in milliseconds, or null if not yet completed. */
    val wallClockMs: Long? = null,
    /** Sessionless, generation-bound VPN route receipt captured by this Home run. */
    val passiveVpnRouteEvidence: NetworkPathValidationEvidence? = null,
)

enum class DiagnosticsHomeDetectionVerdict {
    NOT_DETECTED,
    NEEDS_REVIEW,
    DETECTED,
}

enum class HomeBufferbloatGrade {
    A,
    B,
    C,
    D,
    F,
    UNKNOWN,
}

enum class HomeDnsResolverClass {
    SYSTEM_RESOLVER_OK,
    DOH_PREFERRED,
    POSSIBLE_TRANSPARENT_PROXY,
    POSSIBLE_POISONING,
    DOH_UNREACHABLE,
    UNKNOWN,
}

data class HomeNetworkCharacterSummary(
    val transport: String? = null,
    val operatorOrSsid: String? = null,
    val asn: String? = null,
    val publicIp: String? = null,
    val ipv6Reachable: Boolean? = null,
    val captivePortalDetected: Boolean? = null,
    val mtu: Int? = null,
    val transparentProxyDetected: Boolean? = null,
    val notes: List<String> = emptyList(),
)

data class HomeStrategyEffectivenessEntry(
    val label: String,
    val successCount: Int,
    val failureCount: Int,
)

data class HomeRoutingSanityFinding(
    val packageName: String,
    val severity: String,
    val description: String,
)

data class HomeRoutingSanitySummary(
    val totalConfiguredApps: Int = 0,
    val confirmedDetectorCount: Int = 0,
    val findings: List<HomeRoutingSanityFinding> = emptyList(),
)

data class HomeRegressionDelta(
    val previousRunId: String,
    val newlyFailedStageKeys: List<String> = emptyList(),
    val newlyRecoveredStageKeys: List<String> = emptyList(),
    val unchangedStageCount: Int = 0,
)

data class HomeBufferbloatResult(
    val grade: HomeBufferbloatGrade,
    val idleRttMs: Int? = null,
    val loadedRttMs: Int? = null,
    val deltaMs: Int? = null,
)

data class HomeDnsCharacterization(
    val resolverClass: HomeDnsResolverClass,
    val systemResolver: String? = null,
    val dohEndpoint: String? = null,
    val poisonedHosts: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
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
    val capabilityEvidence: List<DiagnosticsCapabilityEvidence> = emptyList(),
    val directModeVerdict: DirectModeVerdict? = null,
    val recommendedSessionId: String? = null,
    val stageSummaries: List<DiagnosticsHomeCompositeStageSummary> = emptyList(),
    val completedStageCount: Int = 0,
    val failedStageCount: Int = 0,
    val skippedStageCount: Int = 0,
    val bundleSessionIds: List<String> = emptyList(),
    val detectionVerdict: DiagnosticsHomeDetectionVerdict? = null,
    val detectionFindings: List<String> = emptyList(),
    val detectionRuleApplied: String? = null,
    val detectionEvidenceScopes: List<DetectionScope> = emptyList(),
    val detectionSignalCount: Int? = null,
    val detectionLocalFindings: List<String> = emptyList(),
    val detectionNetworkFindings: List<String> = emptyList(),
    val detectionDecisionSignals: List<HomeDetectionDecisionSignal> = emptyList(),
    val installedVpnDetectorCount: Int? = null,
    val installedVpnDetectorTopApps: List<String> = emptyList(),
    val actionableHeadline: String? = null,
    val actionableNextSteps: List<String> = emptyList(),
    val networkCharacter: HomeNetworkCharacterSummary? = null,
    val strategyEffectiveness: List<HomeStrategyEffectivenessEntry> = emptyList(),
    val routingSanity: HomeRoutingSanitySummary? = null,
    val regressionDelta: HomeRegressionDelta? = null,
    val bufferbloat: HomeBufferbloatResult? = null,
    val dnsCharacterization: HomeDnsCharacterization? = null,
    val connectivityAssessment: ConnectivityAssessment? = null,
    val internetLossReproAction: HomeReproAction? = null,
    val packetCaptureDisposition: DiagnosticsHomePacketCaptureDisposition =
        DiagnosticsHomePacketCaptureDisposition.notRequested(),
)

@Serializable
data class DiagnosticsHomePacketCaptureDisposition(
    val requested: Boolean,
    val outcome: DiagnosticsHomePacketCaptureOutcome,
    val captureSetId: Long? = null,
    val totalDrops: Long? = null,
    val failureCode: String? = null,
) {
    init {
        require(captureSetId == null || captureSetId > 0L)
        require(totalDrops == null || totalDrops >= 0L)
        require(failureCode == null || failureCode.matches(SafePacketCaptureFailureCode))
        when (outcome) {
            DiagnosticsHomePacketCaptureOutcome.NOT_REQUESTED -> {
                require(!requested && captureSetId == null && totalDrops == null && failureCode == null)
            }

            DiagnosticsHomePacketCaptureOutcome.UNAVAILABLE -> {
                require(requested && captureSetId == null && totalDrops == null && failureCode != null)
            }

            DiagnosticsHomePacketCaptureOutcome.RECORDING -> {
                // A live lease carries captureSetId. Durable and archive-safe projections omit it.
                require(requested && totalDrops == null)
            }

            DiagnosticsHomePacketCaptureOutcome.CLEANUP_PENDING -> {
                // The report records that capture retirement was not confirmed at export time.
                // captureSetId stays process-local so recovery retains exact ownership.
                require(requested && totalDrops == null && failureCode != null)
            }

            DiagnosticsHomePacketCaptureOutcome.CAPTURED_SEPARATE -> {
                // A live receipt carries captureSetId. The persisted, privacy-safe projection
                // deliberately omits that local identifier and restores the terminal facts only.
                require(requested && totalDrops != null && failureCode == null)
            }

            DiagnosticsHomePacketCaptureOutcome.FAILED -> {
                require(requested && failureCode != null)
            }
        }
    }

    companion object {
        private val SafePacketCaptureFailureCode = Regex("[a-z0-9_]{1,64}")

        fun notRequested(): DiagnosticsHomePacketCaptureDisposition =
            DiagnosticsHomePacketCaptureDisposition(
                requested = false,
                outcome = DiagnosticsHomePacketCaptureOutcome.NOT_REQUESTED,
            )
    }
}

@Serializable
enum class DiagnosticsHomePacketCaptureOutcome {
    @SerialName("not_requested")
    NOT_REQUESTED,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("recording")
    RECORDING,

    @SerialName("cleanup_pending")
    CLEANUP_PENDING,

    @SerialName("captured_separate")
    CAPTURED_SEPARATE,

    @SerialName("failed")
    FAILED,
}

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
    suspend fun startHomeAnalysis(
        options: DiagnosticsHomeRunOptions = DiagnosticsHomeRunOptions(),
    ): DiagnosticsHomeCompositeRunStarted

    suspend fun startQuickAnalysis(
        options: DiagnosticsHomeRunOptions = DiagnosticsHomeRunOptions(),
    ): DiagnosticsHomeCompositeRunStarted

    fun observeHomeRun(runId: String): Flow<DiagnosticsHomeCompositeProgress>

    suspend fun cancelHomeRun(runId: String)

    suspend fun finalizeHomeRun(runId: String): DiagnosticsHomeCompositeOutcome

    suspend fun getCompletedRun(runId: String): DiagnosticsHomeCompositeOutcome?

    suspend fun lookupCachedOutcome(fingerprintHash: String): CachedProbeOutcome?

    suspend fun evictCachedOutcome(fingerprintHash: String)
}

data class DiagnosticsHomeRunOptions(
    val packetCaptureRequested: Boolean = false,
    /** Runs after a stable Home run id is allocated and before any diagnostic stage starts. */
    val admitPacketCapture: suspend (String) -> DiagnosticsHomePacketCaptureDisposition = {
        DiagnosticsHomePacketCaptureDisposition.notRequested()
    },
    /** Settles only this run's capture before the Home outcome becomes terminally observable. */
    val settlePacketCapture: suspend (String) -> DiagnosticsHomePacketCaptureDisposition? = { null },
)

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
    internal abstract fun bindDiagnosticsHomeWorkflowService(
        service: DefaultDiagnosticsHomeWorkflowService,
    ): DiagnosticsHomeWorkflowService

    @Binds
    @Singleton
    internal abstract fun bindDiagnosticsHomeCompositeRunService(
        service: DefaultDiagnosticsHomeCompositeRunService,
    ): DiagnosticsHomeCompositeRunService

    @Binds
    @Singleton
    abstract fun bindProbeResultCache(cache: DefaultProbeResultCache): ProbeResultCache

    // Lets RipDpiApp.onTrimMemory shed the in-memory probe cache when the app is
    // backgrounded (Android 17 per-app memory cap). The persisted file survives;
    // only the regenerable in-memory map is dropped. See [TrimmableCache].
    @Binds
    @IntoSet
    abstract fun bindProbeResultCacheTrimmable(cache: DefaultProbeResultCache): TrimmableCache

    @Binds
    @Singleton
    abstract fun bindLastExitInspector(inspector: DefaultLastExitInspector): LastExitInspector

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
        fun provideDiagnosticsJson(): Json = RipDpiPrettyContractJson

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
        internal fun provideActiveProbeSafetyPolicy(): ActiveProbeSafetyPolicy =
            ActiveProbeSafetyPolicy(
                automaticHandoverProbeDelayMs = secondsToMillis(AutomaticHandoverProbeDelaySeconds),
                automaticHandoverProbeCooldownMs = hoursToMillis(AutomaticHandoverProbeCooldownHours),
                automaticStrategyFailureProbeCooldownMs = hoursToMillis(AutomaticStrategyFailureProbeCooldownHours),
            )

        @Provides
        @Named("importBundledProfilesOnInitialize")
        fun provideImportBundledProfilesOnInitialize(): Boolean = ImportBundledProfilesOnInitialize

        private fun secondsToMillis(seconds: Long): Long = seconds * MillisPerSecond

        private fun hoursToMillis(hours: Long): Long = secondsToMillis(hours * MinutesPerHour * SecondsPerMinute)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsRuntimeEvidenceModule {
    @Binds
    @Singleton
    abstract fun bindProcessExitRuntimeReconciler(
        reconciler: DefaultProcessExitRuntimeReconciler,
    ): ProcessExitRuntimeReconciler

    @Binds
    @Singleton
    internal abstract fun bindProcessExitHistorySource(
        source: AndroidProcessExitHistorySource,
    ): ProcessExitHistorySource

    @Binds
    @Singleton
    abstract fun bindMemoryProfilingRegistrar(registrar: DefaultMemoryProfilingRegistrar): MemoryProfilingRegistrar

    @Binds
    @Singleton
    abstract fun bindNativeMemoryProbe(probe: DefaultNativeMemoryProbe): NativeMemoryProbe
}
