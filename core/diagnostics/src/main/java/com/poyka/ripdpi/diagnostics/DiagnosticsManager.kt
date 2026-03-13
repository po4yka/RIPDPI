package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.deriveStrategyLaneFamilies
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.core.stripRipDpiRuntimeContext
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.BuiltInDnsProviders
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceStrategyProbe
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.services.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.services.DefaultResolverOverrideStore
import com.poyka.ripdpi.services.DefaultPolicyHandoverEventStore
import com.poyka.ripdpi.services.NetworkFingerprintProvider
import com.poyka.ripdpi.services.PolicyHandoverEvent
import com.poyka.ripdpi.services.PolicyHandoverEventStore
import com.poyka.ripdpi.services.ResolverOverrideStore
import com.poyka.ripdpi.services.TemporaryResolverOverride
import com.poyka.ripdpi.services.ServiceStateStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface DiagnosticsManager {
    val activeScanProgress: StateFlow<ScanProgress?>
    val profiles: Flow<List<DiagnosticProfileEntity>>
    val sessions: Flow<List<ScanSessionEntity>>
    val approachStats: Flow<List<BypassApproachSummary>>
    val snapshots: Flow<List<NetworkSnapshotEntity>>
    val contexts: Flow<List<DiagnosticContextEntity>>
    val telemetry: Flow<List<TelemetrySampleEntity>>
    val nativeEvents: Flow<List<NativeSessionEventEntity>>
    val exports: Flow<List<ExportRecordEntity>>

    suspend fun initialize()

    suspend fun startScan(pathMode: ScanPathMode): String

    suspend fun cancelActiveScan()

    suspend fun setActiveProfile(profileId: String)

    suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail

    suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail

    suspend fun buildShareSummary(sessionId: String?): ShareSummary

    suspend fun createArchive(sessionId: String?): DiagnosticsArchive

    suspend fun keepResolverRecommendationForSession(sessionId: String)

    suspend fun saveResolverRecommendation(sessionId: String)
}

@Singleton
class DefaultDiagnosticsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val historyRepository: DiagnosticsHistoryRepository,
        private val logcatSnapshotCollector: LogcatSnapshotCollector = LogcatSnapshotCollector(),
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore =
            DefaultRememberedNetworkPolicyStore(historyRepository),
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore =
            DefaultNetworkDnsPathPreferenceStore(historyRepository),
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val networkFingerprintProvider: NetworkFingerprintProvider =
            object : NetworkFingerprintProvider {
                override fun capture() = null
            },
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
        private val serviceStateStore: ServiceStateStore,
        private val resolverOverrideStore: ResolverOverrideStore = DefaultResolverOverrideStore(),
        private val policyHandoverEventStore: PolicyHandoverEventStore = DefaultPolicyHandoverEventStore(),
        @Named("automaticHandoverProbeDelayMs")
        private val automaticHandoverProbeDelayMs: Long = AutomaticHandoverProbeDelayMs,
        @Named("automaticHandoverProbeCooldownMs")
        private val automaticHandoverProbeCooldownMs: Long = AutomaticHandoverProbeCooldownMs,
        @Named("importBundledProfilesOnInitialize")
        private val importBundledProfilesOnInitialize: Boolean = true,
    ) : DiagnosticsManager {
    private companion object {
        private const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
        private const val FinishedReportPollAttempts = 5
        private const val FinishedReportPollDelayMs = 100L
        private const val AutomaticProbeProfileId = "automatic-probing"
        private const val AutomaticHandoverProbeDelayMs = 15_000L
        private const val AutomaticHandoverProbeCooldownMs = 24L * 60L * 60L * 1_000L
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeScanProgress = MutableStateFlow<ScanProgress?>(null)
    override val activeScanProgress: StateFlow<ScanProgress?> = _activeScanProgress.asStateFlow()
    override val profiles: Flow<List<DiagnosticProfileEntity>> = historyRepository.observeProfiles()
    override val sessions: Flow<List<ScanSessionEntity>> = historyRepository.observeRecentScanSessions()
    override val approachStats: Flow<List<BypassApproachSummary>> =
        combine(
            historyRepository.observeRecentScanSessions(limit = 200),
            historyRepository.observeBypassUsageSessions(limit = 200),
        ) { scanSessions, usageSessions ->
            DiagnosticsSessionQueries.buildApproachSummaries(
                scanSessions = scanSessions,
                usageSessions = usageSessions,
                json = json,
            )
        }
    override val snapshots: Flow<List<NetworkSnapshotEntity>> = historyRepository.observeSnapshots()
    override val contexts: Flow<List<DiagnosticContextEntity>> = historyRepository.observeContexts()
    override val telemetry: Flow<List<TelemetrySampleEntity>> = historyRepository.observeTelemetry()
    override val nativeEvents: Flow<List<NativeSessionEventEntity>> = historyRepository.observeNativeEvents()
    override val exports: Flow<List<ExportRecordEntity>> = historyRepository.observeExportRecords()

    private var activeDiagnosticsBridge: NetworkDiagnosticsBridge? = null
    @Volatile
    private var initialized = false
    private val scanSessionFingerprints = mutableMapOf<String, NetworkFingerprint?>()
    private val scanSessionPreferredDnsPaths = mutableMapOf<String, EncryptedDnsPathCandidate?>()
    private val pendingAutomaticProbeJobs = mutableMapOf<Mode, kotlinx.coroutines.Job>()
    private val recentAutomaticProbeRuns = mutableMapOf<String, Long>()
    private val hiddenScanCount = AtomicInteger(0)

    override suspend fun initialize() {
        if (initialized) {
            return
        }
        initialized = true
        DiagnosticsArchiveBuilder.cleanupCache(context)
        if (importBundledProfilesOnInitialize) {
            importBundledProfiles()
        }
        scope.launch {
            policyHandoverEventStore.events.collect { event ->
                scheduleAutomaticHandoverProbe(event)
            }
        }
    }

    override suspend fun startScan(pathMode: ScanPathMode): String {
        val settings = appSettingsRepository.snapshot()
        val profileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" }
        val profile = requireNotNull(historyRepository.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        return startScanInternal(
            profile = profile,
            settings = settings,
            pathMode = pathMode,
            exposeProgress = true,
            registerActiveBridge = true,
            rawPathRunner = { block -> runtimeCoordinator.runRawPathScan(block) },
        )
    }

    private suspend fun startScanInternal(
        profile: DiagnosticProfileEntity,
        settings: com.poyka.ripdpi.proto.AppSettings,
        pathMode: ScanPathMode,
        exposeProgress: Boolean,
        registerActiveBridge: Boolean,
        rawPathRunner: suspend (suspend () -> Unit) -> Unit,
    ): String {
        val request = json.decodeFromString(ScanRequest.serializer(), profile.requestJson)
        val networkFingerprint = networkFingerprintProvider.capture()
        val preferredDnsPath =
            networkFingerprint
                ?.scopeKey()
                ?.let { fingerprintHash -> networkDnsPathPreferenceStore.getPreferredPath(fingerprintHash) }
        val requestForPath =
            when (request.kind) {
                ScanKind.CONNECTIVITY ->
                    request.copy(
                        pathMode = pathMode,
                        dnsTargets =
                            ConnectivityDnsTargetPlanner.expandTargets(
                                targets = request.dnsTargets,
                                activeDns = settings.activeDnsSettings(),
                                preferredPath = preferredDnsPath,
                            ),
                        proxyHost =
                            if (pathMode == ScanPathMode.IN_PATH) {
                                settings.proxyIp.ifEmpty { "127.0.0.1" }
                            } else {
                                null
                            },
                        proxyPort =
                            if (pathMode == ScanPathMode.IN_PATH) {
                                settings.proxyPort.takeIf { it > 0 } ?: 1080
                            } else {
                                null
                            },
                    )

                ScanKind.STRATEGY_PROBE -> {
                    require(pathMode == ScanPathMode.RAW_PATH) {
                        "Automatic probing only supports raw-path scans"
                    }
                    require(!settings.enableCmdSettings) {
                        "Automatic probing only supports UI-configured RIPDPI settings"
                    }
                    val baseProxyConfigJson =
                        RipDpiProxyUIPreferences(
                            settings = settings,
                            hostAutolearnStorePath = resolveHostAutolearnStorePath(context),
                            runtimeContext = settings.activeDnsSettings().toRipDpiRuntimeContext(),
                        ).toNativeConfigJson()
                    request.copy(
                        pathMode = ScanPathMode.RAW_PATH,
                        proxyHost = null,
                        proxyPort = null,
                        strategyProbe =
                            (request.strategyProbe ?: StrategyProbeRequest()).copy(
                                baseProxyConfigJson = baseProxyConfigJson,
                            ),
                    )
                }
        }
        val sessionId = UUID.randomUUID().toString()
        scanSessionFingerprints[sessionId] = networkFingerprint
        scanSessionPreferredDnsPaths[sessionId] = preferredDnsPath
        val serviceMode = serviceStateStore.status.value.second.name
        val contextSnapshot = diagnosticsContextProvider.captureContext()
        val approachSnapshot =
            createStoredApproachSnapshot(json, settings, profile, contextSnapshot)
        historyRepository.upsertScanSession(
            ScanSessionEntity(
                id = sessionId,
                profileId = profile.id,
                approachProfileId = approachSnapshot.profileId,
                approachProfileName = approachSnapshot.profileName,
                strategyId = approachSnapshot.strategyId,
                strategyLabel = approachSnapshot.strategyLabel,
                strategyJson = approachSnapshot.strategyJson,
                pathMode = pathMode.name,
                serviceMode = serviceMode,
                status = "running",
                summary = "Scan started",
                reportJson = null,
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
            ),
        )
        historyRepository.upsertSnapshot(
            NetworkSnapshotEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                snapshotKind = "pre_scan",
                payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), networkMetadataProvider.captureSnapshot()),
                capturedAt = System.currentTimeMillis(),
            ),
        )
        historyRepository.upsertContextSnapshot(
            DiagnosticContextEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                contextKind = "pre_scan",
                payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), contextSnapshot),
                capturedAt = System.currentTimeMillis(),
            ),
        )

        val bridge = networkDiagnosticsBridgeFactory.create()
        if (registerActiveBridge) {
            activeDiagnosticsBridge = bridge
        } else {
            hiddenScanCount.incrementAndGet()
        }
        try {
            bridge.startScan(
                requestJson = json.encodeToString(ScanRequest.serializer(), requestForPath),
                sessionId = sessionId,
            )
        } catch (error: Throwable) {
            scanSessionFingerprints.remove(sessionId)
            scanSessionPreferredDnsPaths.remove(sessionId)
            if (activeDiagnosticsBridge === bridge) {
                activeDiagnosticsBridge = null
            }
            if (!registerActiveBridge) {
                hiddenScanCount.decrementAndGet()
            }
            runCatching { bridge.destroy() }
            if (exposeProgress) {
                _activeScanProgress.value = null
            }
            DiagnosticsReportPersister.persistScanFailure(sessionId, error.message ?: "Diagnostics scan failed to start", historyRepository)
            throw error
        }
        if (exposeProgress) {
            _activeScanProgress.value =
                ScanProgress(
                    sessionId = sessionId,
                    phase = "preparing",
                    completedSteps = 0,
                    totalSteps = 1,
                    message = "Preparing diagnostics session",
                )
        }

        scope.launch {
            try {
                val scanBlock: suspend () -> Unit = {
                    pollScanResult(
                        sessionId = sessionId,
                        bridge = bridge,
                        settings = settings,
                        exposeProgress = exposeProgress,
                    )
                }

                when (pathMode) {
                    ScanPathMode.RAW_PATH -> rawPathRunner(scanBlock)
                    ScanPathMode.IN_PATH -> scanBlock()
                }
            } catch (error: Throwable) {
                DiagnosticsReportPersister.persistScanFailure(sessionId, error.message ?: "Diagnostics scan failed", historyRepository)
            } finally {
                scanSessionFingerprints.remove(sessionId)
                scanSessionPreferredDnsPaths.remove(sessionId)
                if (exposeProgress) {
                    _activeScanProgress.value = null
                }
                runCatching { bridge.destroy() }
                if (activeDiagnosticsBridge === bridge) {
                    activeDiagnosticsBridge = null
                }
                if (!registerActiveBridge) {
                    hiddenScanCount.decrementAndGet()
                }
            }
        }
        return sessionId
    }

    override suspend fun cancelActiveScan() {
        activeDiagnosticsBridge?.cancelScan()
        _activeScanProgress.value = null
    }

    private fun scheduleAutomaticHandoverProbe(event: PolicyHandoverEvent) {
        pendingAutomaticProbeJobs[event.mode]?.cancel()
        pendingAutomaticProbeJobs[event.mode] =
            scope.launch {
                delay(automaticHandoverProbeDelayMs)
                launchAutomaticHandoverProbe(event)
            }
    }

    private suspend fun launchAutomaticHandoverProbe(event: PolicyHandoverEvent) {
        val settings = appSettingsRepository.snapshot()
        if (!settings.networkStrategyMemoryEnabled || settings.enableCmdSettings || event.usedRememberedPolicy) {
            return
        }
        if (_activeScanProgress.value != null || activeDiagnosticsBridge != null || hiddenScanCount.get() > 0) {
            return
        }

        val probeKey = "${event.mode.preferenceValue}|${event.currentFingerprintHash}"
        val now = System.currentTimeMillis()
        val lastRunAt = recentAutomaticProbeRuns[probeKey]
        if (lastRunAt != null && now - lastRunAt < automaticHandoverProbeCooldownMs) {
            return
        }

        val profile = historyRepository.getProfile(AutomaticProbeProfileId) ?: return
        startScanInternal(
            profile = profile,
            settings = settings,
            pathMode = ScanPathMode.RAW_PATH,
            exposeProgress = false,
            registerActiveBridge = false,
            rawPathRunner = { block -> runtimeCoordinator.runAutomaticRawPathScan(block) },
        )
        recentAutomaticProbeRuns[probeKey] = now
    }

    override suspend fun setActiveProfile(profileId: String) {
        requireNotNull(historyRepository.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        appSettingsRepository.update {
            diagnosticsActiveProfileId = profileId
        }
    }

    override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
        DiagnosticsSessionQueries.loadSessionDetail(
            sessionId = sessionId,
            historyRepository = historyRepository,
        )

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail =
        DiagnosticsSessionQueries.loadApproachDetail(
            kind = kind,
            id = id,
            historyRepository = historyRepository,
            json = json,
        )

    override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
        withContext(Dispatchers.IO) {
            DiagnosticsShareSummaryBuilder.build(
                sessionId = sessionId,
                historyRepository = historyRepository,
                json = json,
            )
        }

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive =
        withContext(Dispatchers.IO) {
            DiagnosticsArchiveBuilder.build(
                sessionId = sessionId,
                context = context,
                historyRepository = historyRepository,
                logcatSnapshotCollector = logcatSnapshotCollector,
                json = json,
                approachSummariesProvider = { scanSessions, usageSessions ->
                    DiagnosticsSessionQueries.buildApproachSummaries(scanSessions, usageSessions, json)
                },
                scanReportDecoder = { payload -> DiagnosticsSessionQueries.decodeScanReport(json, payload) },
            )
        }

    override suspend fun keepResolverRecommendationForSession(sessionId: String) {
        val recommendation = loadResolverRecommendation(sessionId) ?: return
        installTemporaryResolverOverride(recommendation)
    }

    override suspend fun saveResolverRecommendation(sessionId: String) {
        val recommendation = loadResolverRecommendation(sessionId) ?: return
        val selectedPath = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        appSettingsRepository.update {
            dnsMode = com.poyka.ripdpi.data.DnsModeEncrypted
            dnsProviderId = selectedPath.resolverId
            dnsIp = selectedPath.bootstrapIps.firstOrNull().orEmpty()
            dnsDohUrl = selectedPath.dohUrl
            clearDnsDohBootstrapIps()
            addAllDnsDohBootstrapIps(selectedPath.bootstrapIps)
            encryptedDnsProtocol = selectedPath.protocol
            encryptedDnsHost = selectedPath.host
            encryptedDnsPort = selectedPath.port
            encryptedDnsTlsServerName = selectedPath.tlsServerName
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(selectedPath.bootstrapIps)
            encryptedDnsDohUrl = selectedPath.dohUrl
            encryptedDnsDnscryptProviderName = selectedPath.dnscryptProviderName
            encryptedDnsDnscryptPublicKey = selectedPath.dnscryptPublicKey
        }
        (scanSessionFingerprints[sessionId] ?: networkFingerprintProvider.capture())?.let { fingerprint ->
            networkDnsPathPreferenceStore.rememberPreferredPath(fingerprint = fingerprint, path = selectedPath)
        }
        resolverOverrideStore.clear()
    }

    private suspend fun importBundledProfiles() {
        val asset = context.assets.open("diagnostics/default_profiles.json").bufferedReader().use { it.readText() }
        val bundledProfiles = json.decodeFromString(ListSerializer(BundledDiagnosticProfile.serializer()), asset)
        bundledProfiles.forEach { profile ->
            val packVersion = historyRepository.getPackVersion(profile.id)
            if (packVersion == null || packVersion.version < profile.version) {
                historyRepository.upsertProfile(
                    DiagnosticProfileEntity(
                        id = profile.id,
                        name = profile.name,
                        source = "bundled",
                        version = profile.version,
                        requestJson = json.encodeToString(ScanRequest.serializer(), profile.request),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                historyRepository.upsertPackVersion(
                    TargetPackVersionEntity(
                        packId = profile.id,
                        version = profile.version,
                        importedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }


    private suspend fun pollScanResult(
        sessionId: String,
        bridge: NetworkDiagnosticsBridge,
        settings: com.poyka.ripdpi.proto.AppSettings,
        exposeProgress: Boolean,
    ) {
        while (true) {
            DiagnosticsReportPersister.persistNativeEvents(
                sessionId = sessionId,
                payload = bridge.pollPassiveEventsJson(),
                historyRepository = historyRepository,
                json = json,
            )
            val progress =
                bridge.pollProgressJson()
                    ?.let { json.decodeFromString(ScanProgress.serializer(), it) }
            if (exposeProgress) {
                _activeScanProgress.value = progress
            }
            if (progress != null && progress.isFinished) {
                val report =
                    awaitFinishedReportJson(bridge)
                        ?.let { json.decodeFromString(ScanReport.serializer(), it) }
                        ?: throw IllegalStateException("Diagnostics scan completed without a report")
                val enrichedReport = enrichScanReport(report, settings)
                val finalReport = maybeApplyTemporaryResolverOverride(enrichedReport, settings)
                DiagnosticsReportPersister.persistScanReport(finalReport, historyRepository, serviceStateStore, json)
                rememberNetworkDnsPathPreference(sessionId, finalReport.resolverRecommendation)
                rememberStrategyProbeRecommendation(finalReport, settings)
                historyRepository.upsertSnapshot(
                    NetworkSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        snapshotKind = "post_scan",
                        payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), networkMetadataProvider.captureSnapshot()),
                        capturedAt = System.currentTimeMillis(),
                    ),
                )
                historyRepository.upsertContextSnapshot(
                    DiagnosticContextEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        contextKind = "post_scan",
                        payloadJson = json.encodeToString(DiagnosticContextModel.serializer(), diagnosticsContextProvider.captureContext()),
                        capturedAt = System.currentTimeMillis(),
                    ),
                )
                DiagnosticsReportPersister.persistNativeEvents(
                    sessionId = sessionId,
                    payload = bridge.pollPassiveEventsJson(),
                    historyRepository = historyRepository,
                    json = json,
                )
                if (exposeProgress) {
                    _activeScanProgress.value = null
                }
                break
            }
            delay(400L)
        }
    }

    private suspend fun awaitFinishedReportJson(bridge: NetworkDiagnosticsBridge): String? {
        repeat(FinishedReportPollAttempts) { attempt ->
            bridge.takeReportJson()?.let { return it }
            if (attempt < FinishedReportPollAttempts - 1) {
                delay(FinishedReportPollDelayMs)
            }
        }
        return null
    }

    private fun enrichScanReport(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): ScanReport {
        val activeDns = settings.activeDnsSettings()
        val strategyProbe =
            report.strategyProbeReport?.let { strategyProbe ->
                val recommendation = strategyProbe.recommendation
                val laneFamilies =
                    decodeRipDpiProxyUiPreferences(recommendation.recommendedProxyConfigJson)
                        ?.deriveStrategyLaneFamilies(activeDns = activeDns)
                val strategySignature =
                    decodeRipDpiProxyUiPreferences(recommendation.recommendedProxyConfigJson)
                        ?.let { preferences ->
                            deriveBypassStrategySignature(
                                preferences = preferences,
                                routeGroup = null,
                                modeOverride = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }),
                            )
                        }?.copy(
                            dnsStrategyFamily = activeDns.strategyFamily(),
                            dnsStrategyLabel = activeDns.strategyLabel(),
                        )
                val winningTcpCandidate =
                    strategyProbe.tcpCandidates.firstOrNull { it.id == recommendation.tcpCandidateId }
                val winningQuicCandidate =
                    strategyProbe.quicCandidates.firstOrNull { it.id == recommendation.quicCandidateId }
                strategyProbe.copy(
                    recommendation =
                        recommendation.copy(
                            tcpCandidateFamily = winningTcpCandidate?.family ?: laneFamilies?.tcpStrategyFamily,
                            quicCandidateFamily = winningQuicCandidate?.family ?: laneFamilies?.quicStrategyFamily,
                            dnsStrategyFamily = activeDns.strategyFamily(),
                            dnsStrategyLabel = activeDns.strategyLabel(),
                            strategySignature = strategySignature,
                        ),
                )
            }
        val resolverRecommendation =
            ResolverRecommendationEngine.compute(
                report = report,
                settings = settings,
                preferredPath = scanSessionPreferredDnsPaths[report.sessionId],
            )
        return report.copy(
            strategyProbeReport = strategyProbe,
            resolverRecommendation = resolverRecommendation,
        )
    }

    private suspend fun maybeApplyTemporaryResolverOverride(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): ScanReport {
        val recommendation = report.resolverRecommendation ?: return report
        val (status, mode) = serviceStateStore.status.value
        if (
            report.strategyProbeReport != null ||
            mode != Mode.VPN ||
            status != com.poyka.ripdpi.data.AppStatus.Running ||
            settings.activeDnsSettings().mode != DnsModePlainUdp
        ) {
            return report
        }
        installTemporaryResolverOverride(recommendation)
        return report.copy(
            resolverRecommendation = recommendation.copy(appliedTemporarily = true),
        )
    }

    private suspend fun loadResolverRecommendation(sessionId: String): ResolverRecommendation? =
        historyRepository.getScanSession(sessionId)?.reportJson?.let { DiagnosticsSessionQueries.decodeScanReport(json, it) }?.resolverRecommendation

    private suspend fun rememberNetworkDnsPathPreference(
        sessionId: String,
        recommendation: ResolverRecommendation?,
    ) {
        val fingerprint = scanSessionFingerprints[sessionId] ?: return
        val selectedPath = with(ResolverRecommendationEngine) { recommendation?.toEncryptedDnsPathCandidate() } ?: return
        networkDnsPathPreferenceStore.rememberPreferredPath(
            fingerprint = fingerprint,
            path = selectedPath,
        )
    }

    private fun installTemporaryResolverOverride(recommendation: ResolverRecommendation) {
        val selectedPath = with(ResolverRecommendationEngine) { recommendation.toEncryptedDnsPathCandidate() }
        resolverOverrideStore.setTemporaryOverride(
            TemporaryResolverOverride(
                resolverId = selectedPath.resolverId,
                protocol = selectedPath.protocol,
                host = selectedPath.host,
                port = selectedPath.port,
                tlsServerName = selectedPath.tlsServerName,
                bootstrapIps = selectedPath.bootstrapIps,
                dohUrl = selectedPath.dohUrl,
                dnscryptProviderName = selectedPath.dnscryptProviderName,
                dnscryptPublicKey = selectedPath.dnscryptPublicKey,
                reason = recommendation.rationale,
                appliedAt = System.currentTimeMillis(),
            ),
        )
    }


    private suspend fun rememberStrategyProbeRecommendation(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ) {
        if (!settings.networkStrategyMemoryEnabled || settings.enableCmdSettings) {
            return
        }
        val strategyProbe = report.strategyProbeReport ?: return
        if (strategyProbe.suiteId == StrategyProbeSuiteFullMatrixV1) {
            return
        }
        val recommendedCandidateIds =
            setOf(
                strategyProbe.recommendation.tcpCandidateId,
                strategyProbe.recommendation.quicCandidateId,
            )
        val hasWinningTarget =
            (strategyProbe.tcpCandidates + strategyProbe.quicCandidates).any { candidate ->
                candidate.id in recommendedCandidateIds && candidate.succeededTargets > 0
            }
        if (!hasWinningTarget) {
            return
        }
        val winningTcpStrategyFamily =
            strategyProbe.tcpCandidates.firstOrNull { it.id == strategyProbe.recommendation.tcpCandidateId }?.family
        val winningQuicStrategyFamily =
            strategyProbe.quicCandidates.firstOrNull { it.id == strategyProbe.recommendation.quicCandidateId }?.family
        val winningDnsStrategyFamily =
            strategyProbe.recommendation.dnsStrategyFamily ?: settings.activeDnsSettings().strategyFamily()
        val fingerprint = networkFingerprintProvider.capture() ?: return
        val networkScopeKey = fingerprint.scopeKey()
        val normalizedProxyConfigJson =
            stripRipDpiRuntimeContext(
                RipDpiProxyJsonPreferences(
                    configJson = strategyProbe.recommendation.recommendedProxyConfigJson,
                    hostAutolearnStorePath =
                        settings
                            .takeIf { it.hostAutolearnEnabled }
                            ?.let { resolveHostAutolearnStorePath(context) },
                    networkScopeKey = networkScopeKey,
                    runtimeContext = settings.activeDnsSettings().toRipDpiRuntimeContext(),
                ).toNativeConfigJson(),
            )
        val mode = Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })
        val dnsPolicy =
            if (mode == Mode.VPN) {
                settings.activeDnsSettings().toVpnDnsPolicyJson()
            } else {
                null
            }
        rememberedNetworkPolicyStore.rememberValidatedPolicy(
            policy =
                RememberedNetworkPolicyJson(
                    fingerprintHash = networkScopeKey,
                    mode = mode.preferenceValue,
                    summary = fingerprint.summary(),
                    proxyConfigJson = normalizedProxyConfigJson,
                    vpnDnsPolicy = dnsPolicy,
                    strategySignatureJson =
                        strategyProbe.recommendation.strategySignature?.let {
                            json.encodeToString(BypassStrategySignature.serializer(), it)
                        },
                    winningTcpStrategyFamily = winningTcpStrategyFamily,
                    winningQuicStrategyFamily = winningQuicStrategyFamily,
                    winningDnsStrategyFamily = winningDnsStrategyFamily,
                ),
            source = RememberedNetworkPolicySourceStrategyProbe,
            now = report.finishedAt,
        )
    }

    internal suspend fun persistServiceNativeEvents(serviceTelemetry: com.poyka.ripdpi.services.ServiceTelemetrySnapshot) {
        DiagnosticsReportPersister.persistServiceNativeEvents(serviceTelemetry, historyRepository)
    }
}


@Serializable
internal data class RedactedNetworkSummary(
    val transport: String,
    val dnsServers: String,
    val privateDnsMode: String,
    val publicIp: String,
    val publicAsn: String,
    val localAddresses: String,
    val networkValidated: Boolean,
    val captivePortalDetected: Boolean,
    val wifiDetails: RedactedWifiSummary? = null,
    val cellularDetails: RedactedCellularSummary? = null,
)

@Serializable
internal data class RedactedWifiSummary(
    val ssid: String,
    val bssid: String,
    val band: String,
    val wifiStandard: String,
    val frequencyMhz: Int?,
    val linkSpeedMbps: Int?,
    val rssiDbm: Int?,
    val gateway: String,
)

@Serializable
internal data class RedactedCellularSummary(
    val carrierName: String,
    val networkOperatorName: String,
    val dataNetworkType: String,
    val voiceNetworkType: String,
    val networkCountryIso: String,
    val isNetworkRoaming: Boolean?,
    val signalLevel: Int?,
    val signalDbm: Int?,
)

@Serializable
internal data class RedactedServiceContextSummary(
    val serviceStatus: String,
    val activeMode: String,
    val selectedProfileName: String,
    val configSource: String,
    val proxyEndpoint: String,
    val desyncMethod: String,
    val chainSummary: String,
    val routeGroup: String,
    val restartCount: Int,
    val lastNativeErrorHeadline: String,
)

@Serializable
internal data class RedactedPermissionContextSummary(
    val vpnPermissionState: String,
    val notificationPermissionState: String,
    val batteryOptimizationState: String,
    val dataSaverState: String,
)

@Serializable
internal data class RedactedDeviceContextSummary(
    val appVersionName: String,
    val buildType: String,
    val deviceName: String,
    val androidVersion: String,
    val locale: String,
    val timezone: String,
)

@Serializable
internal data class RedactedEnvironmentContextSummary(
    val batterySaverState: String,
    val powerSaveModeState: String,
    val networkMeteredState: String,
    val roamingState: String,
)

@Serializable
internal data class RedactedDiagnosticContextSummary(
    val service: RedactedServiceContextSummary,
    val permissions: RedactedPermissionContextSummary,
    val device: RedactedDeviceContextSummary,
    val environment: RedactedEnvironmentContextSummary,
)

internal fun NetworkSnapshotModel.toRedactedSummary(): RedactedNetworkSummary =
    RedactedNetworkSummary(
        transport = transport,
        dnsServers = if (dnsServers.isEmpty()) "unknown" else "redacted(${dnsServers.size})",
        privateDnsMode = privateDnsMode,
        publicIp = publicIp?.let { "redacted" } ?: "unknown",
        publicAsn = publicAsn?.let { "redacted" } ?: "unknown",
        localAddresses = if (localAddresses.isEmpty()) "unknown" else "redacted(${localAddresses.size})",
        networkValidated = networkValidated,
        captivePortalDetected = captivePortalDetected,
        wifiDetails =
            wifiDetails?.let {
                RedactedWifiSummary(
                    ssid = if (it.ssid == "unknown") "unknown" else "redacted",
                    bssid = if (it.bssid == "unknown") "unknown" else "redacted",
                    band = it.band,
                    wifiStandard = it.wifiStandard,
                    frequencyMhz = it.frequencyMhz,
                    linkSpeedMbps = it.linkSpeedMbps,
                    rssiDbm = it.rssiDbm,
                    gateway = if (it.gateway.isNullOrBlank()) "unknown" else "redacted",
                )
            },
        cellularDetails =
            cellularDetails?.let {
                RedactedCellularSummary(
                    carrierName = it.carrierName,
                    networkOperatorName = it.networkOperatorName,
                    dataNetworkType = it.dataNetworkType,
                    voiceNetworkType = it.voiceNetworkType,
                    networkCountryIso = it.networkCountryIso,
                    isNetworkRoaming = it.isNetworkRoaming,
                    signalLevel = it.signalLevel,
                    signalDbm = it.signalDbm,
                )
            },
    )

internal fun DiagnosticContextModel.toRedactedSummary(): RedactedDiagnosticContextSummary =
    RedactedDiagnosticContextSummary(
        service =
            RedactedServiceContextSummary(
                serviceStatus = service.serviceStatus,
                activeMode = service.activeMode,
                selectedProfileName = service.selectedProfileName,
                configSource = service.configSource,
                proxyEndpoint = if (service.proxyEndpoint == "unknown") "unknown" else "redacted",
                desyncMethod = service.desyncMethod,
                chainSummary = service.chainSummary,
                routeGroup = service.routeGroup,
                restartCount = service.restartCount,
                lastNativeErrorHeadline = service.lastNativeErrorHeadline,
            ),
        permissions =
            RedactedPermissionContextSummary(
                vpnPermissionState = permissions.vpnPermissionState,
                notificationPermissionState = permissions.notificationPermissionState,
                batteryOptimizationState = permissions.batteryOptimizationState,
                dataSaverState = permissions.dataSaverState,
            ),
        device =
            RedactedDeviceContextSummary(
                appVersionName = device.appVersionName,
                buildType = device.buildType,
                deviceName = "${device.manufacturer} ${device.model}",
                androidVersion = "${device.androidVersion} (API ${device.apiLevel})",
                locale = device.locale,
                timezone = device.timezone,
            ),
        environment =
            RedactedEnvironmentContextSummary(
                batterySaverState = environment.batterySaverState,
                powerSaveModeState = environment.powerSaveModeState,
                networkMeteredState = environment.networkMeteredState,
                roamingState = environment.roamingState,
            ),
    )

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsManagerModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsManager(
        manager: DefaultDiagnosticsManager,
    ): DiagnosticsManager

    companion object {
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
