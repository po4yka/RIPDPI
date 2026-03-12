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
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.RememberedNetworkPolicySourceStrategyProbe
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.strategyFamily
import com.poyka.ripdpi.data.strategyLabel
import com.poyka.ripdpi.data.toVpnDnsPolicyJson
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.services.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.services.DefaultResolverOverrideStore
import com.poyka.ripdpi.services.NetworkFingerprintProvider
import com.poyka.ripdpi.services.ResolverOverrideStore
import com.poyka.ripdpi.services.TemporaryResolverOverride
import com.poyka.ripdpi.services.ServiceStateStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
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
import kotlinx.coroutines.flow.first
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
    ) : DiagnosticsManager {
    private companion object {
        private const val DiagnosticsArchiveDirectory = "diagnostics-archives"
        private const val DiagnosticsArchivePrefix = "ripdpi-diagnostics-"
        private const val ArchiveSchemaVersion = 7
        private const val ArchivePrivacyMode = "split_output"
        private const val ArchiveScopeHybrid = "hybrid"
        private const val StrategyProbeSuiteFullMatrixV1 = "full_matrix_v1"
        private const val MaxArchiveFiles = 5
        private const val MaxArchiveAgeMs = 3L * 24L * 60L * 60L * 1000L
        private const val ArchiveTelemetryLimit = 120
        private const val ArchiveGlobalEventLimit = 80
        private const val ArchiveSnapshotLimit = 250
        private const val FinishedReportPollAttempts = 5
        private const val FinishedReportPollDelayMs = 100L
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
            buildApproachSummaries(scanSessions = scanSessions, usageSessions = usageSessions)
        }
    override val snapshots: Flow<List<NetworkSnapshotEntity>> = historyRepository.observeSnapshots()
    override val contexts: Flow<List<DiagnosticContextEntity>> = historyRepository.observeContexts()
    override val telemetry: Flow<List<TelemetrySampleEntity>> = historyRepository.observeTelemetry()
    override val nativeEvents: Flow<List<NativeSessionEventEntity>> = historyRepository.observeNativeEvents()
    override val exports: Flow<List<ExportRecordEntity>> = historyRepository.observeExportRecords()

    private var activeDiagnosticsBridge: NetworkDiagnosticsBridge? = null
    @Volatile
    private var initialized = false

    override suspend fun initialize() {
        if (initialized) {
            return
        }
        initialized = true
        cleanupArchiveCache()
        importBundledProfiles()
    }

    override suspend fun startScan(pathMode: ScanPathMode): String {
        val settings = appSettingsRepository.snapshot()
        val profileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" }
        val profile = requireNotNull(historyRepository.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        val request = json.decodeFromString(ScanRequest.serializer(), profile.requestJson)
        val requestForPath =
            when (request.kind) {
                ScanKind.CONNECTIVITY ->
                    request.copy(
                        pathMode = pathMode,
                        dnsTargets = expandConnectivityDnsTargets(request.dnsTargets),
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
        val serviceMode = serviceStateStore.status.value.second.name
        val contextSnapshot = diagnosticsContextProvider.captureContext()
        val approachSnapshot =
            createStoredApproachSnapshot(json, settings, profile, contextSnapshot)
        historyRepository.upsertScanSession(
            ScanSessionEntity(
                id = sessionId,
                profileId = profileId,
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

        val bridge = networkDiagnosticsBridgeFactory.create().also { activeDiagnosticsBridge = it }
        try {
            bridge.startScan(
                requestJson = json.encodeToString(ScanRequest.serializer(), requestForPath),
                sessionId = sessionId,
            )
        } catch (error: Throwable) {
            activeDiagnosticsBridge = null
            runCatching { bridge.destroy() }
            _activeScanProgress.value = null
            persistScanFailure(sessionId, error.message ?: "Diagnostics scan failed to start")
            throw error
        }
        _activeScanProgress.value =
            ScanProgress(
                sessionId = sessionId,
                phase = "preparing",
                completedSteps = 0,
                totalSteps = 1,
                message = "Preparing diagnostics session",
            )

        scope.launch {
            try {
                val scanBlock: suspend () -> Unit = {
                    pollScanResult(sessionId, bridge, settings)
                }

                when (pathMode) {
                    ScanPathMode.RAW_PATH -> runtimeCoordinator.runRawPathScan(scanBlock)
                    ScanPathMode.IN_PATH -> scanBlock()
                }
            } catch (error: Throwable) {
                persistScanFailure(sessionId, error.message ?: "Diagnostics scan failed")
            } finally {
                _activeScanProgress.value = null
                runCatching { bridge.destroy() }
                if (activeDiagnosticsBridge === bridge) {
                    activeDiagnosticsBridge = null
                }
            }
        }
        return sessionId
    }

    override suspend fun cancelActiveScan() {
        activeDiagnosticsBridge?.cancelScan()
        _activeScanProgress.value = null
    }

    override suspend fun setActiveProfile(profileId: String) {
        requireNotNull(historyRepository.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        appSettingsRepository.update {
            diagnosticsActiveProfileId = profileId
        }
    }

    override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
        withContext(Dispatchers.IO) {
            val session = requireNotNull(historyRepository.getScanSession(sessionId)) { "Unknown diagnostics session: $sessionId" }
            val results = historyRepository.getProbeResults(sessionId)
            val snapshots =
                historyRepository.observeSnapshots(limit = 200).first().filter { it.sessionId == sessionId }
            val latestContext =
                historyRepository
                    .observeContexts(limit = 200)
                    .first()
                    .filter { it.sessionId == sessionId }
                    .maxByOrNull { it.capturedAt }
            val events =
                historyRepository.observeNativeEvents(limit = 500).first().filter { it.sessionId == sessionId }
            DiagnosticSessionDetail(
                session = session,
                results = results,
                snapshots = snapshots,
                events = events,
                context = latestContext,
            )
        }

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail =
        withContext(Dispatchers.IO) {
            val sessions = historyRepository.observeRecentScanSessions(limit = 200).first()
            val usageSessions = historyRepository.observeBypassUsageSessions(limit = 200).first()
            val summary =
                buildApproachSummaries(scanSessions = sessions, usageSessions = usageSessions)
                    .firstOrNull { it.approachId.kind == kind && it.approachId.value == id }
                    ?: throw IllegalArgumentException("Unknown bypass approach: $kind/$id")

            val matchingSessions =
                sessions.filter { session ->
                    when (kind) {
                        BypassApproachKind.Profile -> session.approachProfileId == id || session.profileId == id
                        BypassApproachKind.Strategy -> session.strategyId == id
                    }
                }
            val matchingUsageSessions =
                usageSessions.filter { usage ->
                    when (kind) {
                        BypassApproachKind.Profile -> usage.approachProfileId == id
                        BypassApproachKind.Strategy -> usage.strategyId == id
                    }
                }
            val failureNotes =
                matchingSessions
                    .flatMap { session ->
                        decodeScanReport(session.reportJson)
                            ?.results
                            .orEmpty()
                            .filterNot { it.outcome.isSuccessfulOutcome() }
                            .map { result -> "${result.probeType}:${result.target}=${result.outcome}" }
                    }.take(8)
            val strategySignature =
                when (kind) {
                    BypassApproachKind.Profile ->
                        matchingSessions
                            .firstNotNullOfOrNull { decodeStrategySignature(it.strategyJson) }
                            ?: matchingUsageSessions.firstNotNullOfOrNull { decodeStrategySignature(it.strategyJson) }
                    BypassApproachKind.Strategy ->
                        matchingSessions
                            .firstNotNullOfOrNull { decodeStrategySignature(it.strategyJson) }
                            ?: matchingUsageSessions.firstNotNullOfOrNull { decodeStrategySignature(it.strategyJson) }
                }

            BypassApproachDetail(
                summary = summary,
                strategySignature = strategySignature,
                recentValidatedSessions = matchingSessions.take(6),
                recentUsageSessions = matchingUsageSessions.take(6),
                commonProbeFailures = summary.topFailureOutcomes,
                recentFailureNotes = failureNotes,
            )
        }

    override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
        withContext(Dispatchers.IO) {
            val selectedSession =
                sessionId
                    ?.let { id -> historyRepository.getScanSession(id) }
                    ?: historyRepository.observeRecentScanSessions(limit = 1).first().firstOrNull()
            val selectedResults =
                selectedSession?.id?.let { id -> historyRepository.getProbeResults(id) }.orEmpty()
            val latestSnapshot =
                selectedSession
                    ?.id
                    ?.let { id -> historyRepository.observeSnapshots(limit = 200).first().firstOrNull { it.sessionId == id } }
                    ?: historyRepository.observeSnapshots(limit = 1).first().firstOrNull()
            val latestSnapshotModel =
                latestSnapshot?.payloadJson
                    ?.let { payload -> runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payload) }.getOrNull() }
            val latestContext =
                selectedSession
                    ?.id
                    ?.let { id ->
                        historyRepository.observeContexts(limit = 200).first().firstOrNull { it.sessionId == id }
                    }
                    ?: historyRepository.observeContexts(limit = 1).first().firstOrNull()
            val latestContextModel =
                latestContext?.payloadJson
                    ?.let { payload -> runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payload) }.getOrNull() }
            val latestTelemetry = historyRepository.observeTelemetry(limit = 1).first().firstOrNull()
            val latestWarnings =
                historyRepository.observeNativeEvents(limit = 50).first().filter {
                    it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
                }
            val title =
                selectedSession?.let { "RIPDPI diagnostics ${it.id.take(8)}" } ?: "RIPDPI diagnostics summary"
            val body =
                buildString {
                    appendLine("RIPDPI diagnostics summary")
                    appendLine("session=${selectedSession?.id ?: "latest-live"}")
                    selectedSession?.let { session ->
                        appendLine("pathMode=${session.pathMode}")
                        appendLine("serviceMode=${session.serviceMode ?: "unknown"}")
                        appendLine("status=${session.status}")
                        appendLine("summary=${session.summary}")
                        appendLine("startedAt=${session.startedAt}")
                        appendLine("finishedAt=${session.finishedAt ?: "running"}")
                    }
                    latestSnapshotModel?.toRedactedSummary()?.let { snapshot ->
                        appendLine("transport=${snapshot.transport}")
                        appendLine("publicIp=${snapshot.publicIp}")
                        appendLine("publicAsn=${snapshot.publicAsn}")
                        appendLine("dns=${snapshot.dnsServers}")
                        appendLine("privateDns=${snapshot.privateDnsMode}")
                        appendLine("validated=${snapshot.networkValidated}")
                        snapshot.wifiDetails?.let { wifi ->
                            appendLine("wifiSsid=${wifi.ssid}")
                            appendLine("wifiBand=${wifi.band}")
                            appendLine("wifiStandard=${wifi.wifiStandard}")
                            appendLine("wifiSignal=${wifi.rssiDbm ?: "unknown"}")
                        }
                        snapshot.cellularDetails?.let { cellular ->
                            appendLine("carrier=${cellular.carrierName}")
                            appendLine("networkOperator=${cellular.networkOperatorName}")
                            appendLine("dataNetwork=${cellular.dataNetworkType}")
                            appendLine("voiceNetwork=${cellular.voiceNetworkType}")
                            appendLine("roaming=${cellular.isNetworkRoaming ?: "unknown"}")
                        }
                    }
                    latestContextModel?.toRedactedSummary()?.let { contextSummary ->
                        appendLine("appVersion=${contextSummary.device.appVersionName}")
                        appendLine("device=${contextSummary.device.deviceName}")
                        appendLine("android=${contextSummary.device.androidVersion}")
                        appendLine("serviceMode=${contextSummary.service.activeMode}")
                        appendLine("profile=${contextSummary.service.selectedProfileName}")
                        appendLine("configSource=${contextSummary.service.configSource}")
                        appendLine("proxy=${contextSummary.service.proxyEndpoint}")
                        appendLine("vpnPermission=${contextSummary.permissions.vpnPermissionState}")
                        appendLine("notifications=${contextSummary.permissions.notificationPermissionState}")
                        appendLine("batteryOptimization=${contextSummary.permissions.batteryOptimizationState}")
                        appendLine("dataSaver=${contextSummary.permissions.dataSaverState}")
                    }
                    latestTelemetry?.let { telemetry ->
                        appendLine("networkType=${telemetry.networkType}")
                        appendLine("failureClass=${telemetry.failureClass ?: "none"}")
                        appendLine("winningStrategyFamily=${telemetry.winningStrategyFamily() ?: "none"}")
                        appendLine("telemetryNetworkFingerprintHash=${telemetry.telemetryNetworkFingerprintHash ?: "none"}")
                        appendLine("rttBand=${telemetry.rttBand()}")
                        appendLine("retryCount=${telemetry.retryCount()}")
                        appendLine("resolverId=${telemetry.resolverId ?: "unknown"}")
                        appendLine("resolverProtocol=${telemetry.resolverProtocol ?: "unknown"}")
                        appendLine("resolverEndpoint=${telemetry.resolverEndpoint ?: "unknown"}")
                        appendLine("resolverLatencyMs=${telemetry.resolverLatencyMs ?: 0}")
                        appendLine("dnsFailuresTotal=${telemetry.dnsFailuresTotal}")
                        appendLine("resolverFallback=${telemetry.resolverFallbackReason ?: telemetry.resolverFallbackActive}")
                        appendLine("networkHandoverClass=${telemetry.networkHandoverClass ?: "none"}")
                        appendLine("txBytes=${telemetry.txBytes}")
                        appendLine("rxBytes=${telemetry.rxBytes}")
                        appendLine("txPackets=${telemetry.txPackets}")
                        appendLine("rxPackets=${telemetry.rxPackets}")
                    }
                    if (selectedResults.isNotEmpty()) {
                        appendLine("results=${selectedResults.size}")
                        selectedResults.take(5).forEach { result ->
                            appendLine("${result.probeType}:${result.target}=${result.outcome}")
                        }
                    }
                    if (latestWarnings.isNotEmpty()) {
                        appendLine("warnings=")
                        latestWarnings.take(3).forEach { warning ->
                            appendLine("- ${warning.source}: ${warning.message}")
                        }
                    }
                }
            ShareSummary(
                title = title,
                body = body.trim(),
                compactMetrics =
                    listOfNotNull(
                        selectedSession?.pathMode?.let { SummaryMetric(label = "Path", value = it) },
                        latestSnapshotModel?.transport?.let { SummaryMetric(label = "Transport", value = it) },
                        latestContextModel?.service?.activeMode?.let { SummaryMetric(label = "Mode", value = it) },
                        latestContextModel?.device?.appVersionName?.let { SummaryMetric(label = "App", value = it) },
                        latestTelemetry?.txBytes?.let { SummaryMetric(label = "TX", value = it.toString()) },
                        latestTelemetry?.rxBytes?.let { SummaryMetric(label = "RX", value = it.toString()) },
                    ),
            )
        }

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive =
        withContext(Dispatchers.IO) {
            cleanupArchiveCache()
            val archiveDirectory = ensureArchiveDirectory()
            val timestamp = System.currentTimeMillis()
            val fileName = "$DiagnosticsArchivePrefix$timestamp.zip"
            val target = File(archiveDirectory, fileName)
            val sessions = historyRepository.observeRecentScanSessions(limit = 50).first()
            val usageSessions = historyRepository.observeBypassUsageSessions(limit = 120).first()
            val snapshots = historyRepository.observeSnapshots(limit = ArchiveSnapshotLimit).first()
            val telemetry = historyRepository.observeTelemetry(limit = ArchiveTelemetryLimit).first()
            val events = historyRepository.observeNativeEvents(limit = ArchiveGlobalEventLimit).first()
            val contexts = historyRepository.observeContexts(limit = ArchiveSnapshotLimit).first()
            val logcatSnapshot = runCatching { logcatSnapshotCollector.capture() }.getOrNull()
            val approachSummaries = buildApproachSummaries(scanSessions = sessions, usageSessions = usageSessions)
            val primarySession =
                sessionId
                    ?.let { historyRepository.getScanSession(it) }
                    ?: sessions.firstOrNull { it.reportJson != null }
                    ?: sessions.firstOrNull()
            val primaryReport = decodeScanReport(primarySession?.reportJson)
            val primaryResults = primarySession?.id?.let { historyRepository.getProbeResults(it) }.orEmpty()
            val primarySnapshots = primarySession?.id?.let { id -> snapshots.filter { it.sessionId == id } }.orEmpty()
            val primaryContexts = primarySession?.id?.let { id -> contexts.filter { it.sessionId == id } }.orEmpty()
            val primaryEvents = primarySession?.id?.let { id -> events.filter { it.sessionId == id } }.orEmpty()
            val latestPassiveSnapshot = snapshots.firstOrNull { it.sessionId == null }
            val latestPassiveContext = contexts.firstOrNull { it.sessionId == null }
            val globalEvents =
                events
                    .filter { it.sessionId == null || it.sessionId != primarySession?.id }
                    .take(ArchiveGlobalEventLimit)
            val payload =
                DiagnosticsArchivePayload(
                    schemaVersion = ArchiveSchemaVersion,
                    scope = ArchiveScopeHybrid,
                    privacyMode = ArchivePrivacyMode,
                    session = primarySession,
                    results = primaryResults,
                    sessionSnapshots = primarySnapshots,
                    sessionContexts = primaryContexts,
                    sessionEvents = primaryEvents,
                    latestPassiveSnapshot = latestPassiveSnapshot,
                    latestPassiveContext = latestPassiveContext,
                    telemetry = telemetry.take(ArchiveTelemetryLimit),
                    globalEvents = globalEvents,
                    approachSummaries = approachSummaries,
                )
            val selectedApproachSummary =
                primarySession?.strategyId?.let { strategyId ->
                    approachSummaries.firstOrNull {
                        it.approachId.kind == BypassApproachKind.Strategy &&
                            it.approachId.value == strategyId
                    }
                }
            val primarySnapshotModel =
                primarySnapshots
                    .maxByOrNull { it.capturedAt }
                    ?.payloadJson
                    ?.let { payloadJson ->
                        runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payloadJson) }.getOrNull()
                    }
            val latestSnapshotModel =
                latestPassiveSnapshot
                    ?.payloadJson
                    ?.let { payloadJson ->
                        runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payloadJson) }.getOrNull()
                    }
                    ?: primarySnapshotModel
            val latestContextModel =
                latestPassiveContext
                    ?.payloadJson
                    ?.let { payloadJson ->
                        runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payloadJson) }.getOrNull()
                    }
            val sessionContextModel =
                primaryContexts
                    .maxByOrNull { it.capturedAt }
                    ?.payloadJson
                    ?.let { payloadJson ->
                        runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payloadJson) }.getOrNull()
                    }
            val includedFiles =
                buildList {
                    add("summary.txt")
                    add("manifest.json")
                    add("report.json")
                    add("strategy-matrix.json")
                    add("probe-results.csv")
                    add("native-events.csv")
                    add("telemetry.csv")
                    add("network-snapshots.json")
                    add("diagnostic-context.json")
                    if (logcatSnapshot != null) {
                        add("logcat.txt")
                    }
                }
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("summary.txt"))
                val summary =
                    buildArchiveSummary(
                        createdAt = timestamp,
                        session = primarySession,
                        results = primaryResults,
                        latestSnapshot = latestSnapshotModel,
                        latestContext = sessionContextModel ?: latestContextModel,
                        telemetry = telemetry,
                        globalEvents = globalEvents,
                        selectedApproach = selectedApproachSummary,
                        logcatSnapshot = logcatSnapshot,
                    )
                zip.write(summary.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("report.json"))
                val reportJson = json.encodeToString(DiagnosticsArchivePayload.serializer(), payload)
                zip.write(reportJson.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("strategy-matrix.json"))
                val strategyMatrixJson =
                    json.encodeToString(
                        StrategyMatrixArchivePayload.serializer(),
                        StrategyMatrixArchivePayload(
                            sessionId = primarySession?.id,
                            profileId = primarySession?.profileId,
                            strategyProbeReport = primaryReport?.strategyProbeReport,
                        ),
                    )
                zip.write(strategyMatrixJson.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("probe-results.csv"))
                zip.write(buildProbeResultsCsv(primaryResults).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("native-events.csv"))
                zip.write(buildNativeEventsCsv(primaryEvents = primaryEvents, globalEvents = globalEvents).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("network-snapshots.json"))
                val snapshotsJson =
                    json.encodeToString(
                        DiagnosticsArchiveSnapshotPayload.serializer(),
                        DiagnosticsArchiveSnapshotPayload(
                            sessionSnapshots = primarySnapshots,
                            latestPassiveSnapshot = latestPassiveSnapshot,
                        ),
                    )
                zip.write(snapshotsJson.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("diagnostic-context.json"))
                val contextsJson =
                    json.encodeToString(
                        DiagnosticsArchiveContextPayload.serializer(),
                        DiagnosticsArchiveContextPayload(
                            sessionContexts = primaryContexts,
                            latestPassiveContext = latestPassiveContext,
                        ),
                    )
                zip.write(contextsJson.toByteArray())
                zip.closeEntry()

                logcatSnapshot?.let { snapshot ->
                    zip.putNextEntry(ZipEntry("logcat.txt"))
                    zip.write(snapshot.content.toByteArray())
                    zip.closeEntry()
                }

                zip.putNextEntry(ZipEntry("telemetry.csv"))
                val csv =
                    buildString {
                        appendLine(
                            "createdAt,activeMode,connectionState,networkType,publicIp,failureClass," +
                                "lastFailureClass,lastFallbackAction," +
                                "telemetryNetworkFingerprintHash,winningTcpStrategyFamily,winningQuicStrategyFamily," +
                                "winningStrategyFamily,proxyRttBand,resolverRttBand,rttBand,proxyRouteRetryCount," +
                                "tunnelRecoveryRetryCount,retryCount,resolverId,resolverProtocol," +
                                "resolverEndpoint,resolverLatencyMs,dnsFailuresTotal,resolverFallbackActive," +
                                "resolverFallbackReason,networkHandoverClass,txPackets,txBytes,rxPackets,rxBytes",
                        )
                        payload.telemetry.forEach { sample ->
                            appendLine(
                                listOf(
                                    sample.createdAt,
                                    sample.activeMode.orEmpty(),
                                    sample.connectionState,
                                    sample.networkType,
                                    sample.publicIp.orEmpty(),
                                    sample.failureClass.orEmpty(),
                                    sample.lastFailureClass.orEmpty(),
                                    sample.lastFallbackAction.orEmpty(),
                                    sample.telemetryNetworkFingerprintHash.orEmpty(),
                                    sample.winningTcpStrategyFamily.orEmpty(),
                                    sample.winningQuicStrategyFamily.orEmpty(),
                                    sample.winningStrategyFamily().orEmpty(),
                                    sample.proxyRttBand,
                                    sample.resolverRttBand,
                                    sample.rttBand(),
                                    sample.proxyRouteRetryCount,
                                    sample.tunnelRecoveryRetryCount,
                                    sample.retryCount(),
                                    sample.resolverId.orEmpty(),
                                    sample.resolverProtocol.orEmpty(),
                                    sample.resolverEndpoint.orEmpty(),
                                    sample.resolverLatencyMs ?: 0,
                                    sample.dnsFailuresTotal,
                                    sample.resolverFallbackActive,
                                    sample.resolverFallbackReason.orEmpty(),
                                    sample.networkHandoverClass.orEmpty(),
                                    sample.txPackets,
                                    sample.txBytes,
                                    sample.rxPackets,
                                    sample.rxBytes,
                                ).joinToString(","),
                            )
                        }
                    }
                zip.write(csv.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("manifest.json"))
                val manifest =
                    json.encodeToString(
                        DiagnosticsArchiveManifest.serializer(),
                        DiagnosticsArchiveManifest(
                            fileName = fileName,
                            createdAt = timestamp,
                            schemaVersion = ArchiveSchemaVersion,
                            privacyMode = ArchivePrivacyMode,
                            scope = ArchiveScopeHybrid,
                            includedSessionId = primarySession?.id,
                            sessionResultCount = primaryResults.size,
                            sessionSnapshotCount = primarySnapshots.size,
                            contextSnapshotCount = primaryContexts.size,
                            sessionEventCount = primaryEvents.size,
                            telemetrySampleCount = payload.telemetry.size,
                            globalEventCount = globalEvents.size,
                            approachCount = approachSummaries.size,
                            selectedApproach = selectedApproachSummary,
                            networkSummary = latestSnapshotModel?.toRedactedSummary(),
                            contextSummary = (sessionContextModel ?: latestContextModel)?.toRedactedSummary(),
                            latestTelemetrySummary = payload.telemetry.firstOrNull()?.toArchiveTelemetrySummary(),
                            includedFiles = includedFiles,
                            logcatIncluded = logcatSnapshot != null,
                            logcatCaptureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                            logcatByteCount = logcatSnapshot?.byteCount ?: 0,
                        ),
                    )
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            }
            historyRepository.insertExportRecord(
                ExportRecordEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = primarySession?.id,
                    uri = target.absolutePath,
                    fileName = fileName,
                    createdAt = timestamp,
                ),
            )
            DiagnosticsArchive(
                fileName = fileName,
                absolutePath = target.absolutePath,
                sessionId = primarySession?.id,
                createdAt = timestamp,
                scope = ArchiveScopeHybrid,
                schemaVersion = ArchiveSchemaVersion,
                privacyMode = ArchivePrivacyMode,
            )
        }

    override suspend fun keepResolverRecommendationForSession(sessionId: String) {
        val recommendation = loadResolverRecommendation(sessionId) ?: return
        installTemporaryResolverOverride(recommendation)
    }

    override suspend fun saveResolverRecommendation(sessionId: String) {
        val recommendation = loadResolverRecommendation(sessionId) ?: return
        val provider =
            BuiltInDnsProviders.firstOrNull { it.providerId == recommendation.selectedResolverId }
                ?: return
        appSettingsRepository.update {
            dnsMode = com.poyka.ripdpi.data.DnsModeEncrypted
            dnsProviderId = provider.providerId
            dnsIp = provider.primaryIp
            dnsDohUrl = provider.dohUrl.orEmpty()
            clearDnsDohBootstrapIps()
            addAllDnsDohBootstrapIps(provider.bootstrapIps)
            encryptedDnsProtocol = provider.protocol
            encryptedDnsHost = provider.host
            encryptedDnsPort = provider.port
            encryptedDnsTlsServerName = provider.tlsServerName
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(provider.bootstrapIps)
            encryptedDnsDohUrl = provider.dohUrl.orEmpty()
            encryptedDnsDnscryptProviderName = provider.dnscryptProviderName.orEmpty()
            encryptedDnsDnscryptPublicKey = provider.dnscryptPublicKey.orEmpty()
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

    private fun ensureArchiveDirectory(): File =
        File(context.cacheDir, DiagnosticsArchiveDirectory).apply {
            mkdirs()
        }

    private fun cleanupArchiveCache() {
        val archiveDirectory = ensureArchiveDirectory()
        val now = System.currentTimeMillis()
        val archiveFiles =
            archiveDirectory
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith(DiagnosticsArchivePrefix) && it.extension == "zip" }
                .sortedByDescending { it.lastModified() }

        archiveFiles
            .filter { now - it.lastModified() > MaxArchiveAgeMs }
            .forEach { it.delete() }

        archiveDirectory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(DiagnosticsArchivePrefix) && it.extension == "zip" }
            .sortedByDescending { it.lastModified() }
            .drop(MaxArchiveFiles)
            .forEach { it.delete() }
    }

    private fun buildArchiveSummary(
        createdAt: Long,
        session: ScanSessionEntity?,
        results: List<ProbeResultEntity>,
        latestSnapshot: NetworkSnapshotModel?,
        latestContext: DiagnosticContextModel?,
        telemetry: List<TelemetrySampleEntity>,
        globalEvents: List<NativeSessionEventEntity>,
        selectedApproach: BypassApproachSummary?,
        logcatSnapshot: LogcatSnapshot?,
    ): String =
        buildString {
            appendLine("RIPDPI diagnostics archive")
            appendLine("generatedAt=$createdAt")
            appendLine("scope=$ArchiveScopeHybrid")
            appendLine("privacyMode=$ArchivePrivacyMode")
            appendLine("logcatIncluded=${logcatSnapshot != null}")
            appendLine("logcatCaptureScope=${LogcatSnapshotCollector.AppVisibleSnapshotScope}")
            appendLine("logcatByteCount=${logcatSnapshot?.byteCount ?: 0}")
            appendLine("selectedSession=${session?.id ?: "latest-live"}")
            session?.let {
                appendLine("pathMode=${it.pathMode}")
                appendLine("serviceMode=${it.serviceMode ?: "unknown"}")
                appendLine("status=${it.status}")
                appendLine("summary=${it.summary}")
                decodeScanReport(it.reportJson)?.strategyProbeReport?.let { strategyProbe ->
                    appendLine("strategySuite=${strategyProbe.suiteId}")
                    appendLine("strategyTcpCandidates=${strategyProbe.tcpCandidates.size}")
                    appendLine("strategyQuicCandidates=${strategyProbe.quicCandidates.size}")
                }
            }
            selectedApproach?.let {
                appendLine("approach=${it.displayName}")
                appendLine("approachVerification=${it.verificationState}")
                appendLine("approachSuccessRate=${it.validatedSuccessRate?.let { rate -> "${(rate * 100).toInt()}%" } ?: "unverified"}")
                appendLine("approachUsageCount=${it.usageCount}")
                appendLine("approachRuntimeMs=${it.totalRuntimeDurationMs}")
            }
            latestSnapshot?.toRedactedSummary()?.let { summary ->
                appendLine("transport=${summary.transport}")
                appendLine("dns=${summary.dnsServers}")
                appendLine("privateDns=${summary.privateDnsMode}")
                appendLine("publicIp=${summary.publicIp}")
                appendLine("publicAsn=${summary.publicAsn}")
                appendLine("localAddresses=${summary.localAddresses}")
                appendLine("validated=${summary.networkValidated}")
                appendLine("captivePortal=${summary.captivePortalDetected}")
                summary.wifiDetails?.let { wifi ->
                    appendLine("wifiSsid=${wifi.ssid}")
                    appendLine("wifiBand=${wifi.band}")
                    appendLine("wifiStandard=${wifi.wifiStandard}")
                    appendLine("wifiFrequencyMhz=${wifi.frequencyMhz ?: "unknown"}")
                    appendLine("wifiLinkSpeedMbps=${wifi.linkSpeedMbps ?: "unknown"}")
                    appendLine("wifiSignalDbm=${wifi.rssiDbm ?: "unknown"}")
                    appendLine("wifiGateway=${wifi.gateway}")
                }
                summary.cellularDetails?.let { cellular ->
                    appendLine("carrier=${cellular.carrierName}")
                    appendLine("networkOperator=${cellular.networkOperatorName}")
                    appendLine("dataNetwork=${cellular.dataNetworkType}")
                    appendLine("voiceNetwork=${cellular.voiceNetworkType}")
                    appendLine("networkCountry=${cellular.networkCountryIso}")
                    appendLine("roaming=${cellular.isNetworkRoaming ?: "unknown"}")
                    appendLine("signalLevel=${cellular.signalLevel ?: "unknown"}")
                    appendLine("signalDbm=${cellular.signalDbm ?: "unknown"}")
                }
            }
            latestContext?.toRedactedSummary()?.let { contextSummary ->
                appendLine("appVersion=${contextSummary.device.appVersionName}")
                appendLine("device=${contextSummary.device.deviceName}")
                appendLine("android=${contextSummary.device.androidVersion}")
                appendLine("serviceMode=${contextSummary.service.activeMode}")
                appendLine("serviceStatus=${contextSummary.service.serviceStatus}")
                appendLine("profile=${contextSummary.service.selectedProfileName}")
                appendLine("configSource=${contextSummary.service.configSource}")
                appendLine("proxyEndpoint=${contextSummary.service.proxyEndpoint}")
                appendLine("desyncMethod=${contextSummary.service.desyncMethod}")
                appendLine("chainSummary=${contextSummary.service.chainSummary}")
                appendLine("lastNativeError=${contextSummary.service.lastNativeErrorHeadline}")
                appendLine("vpnPermission=${contextSummary.permissions.vpnPermissionState}")
                appendLine("notifications=${contextSummary.permissions.notificationPermissionState}")
                appendLine("batteryOptimization=${contextSummary.permissions.batteryOptimizationState}")
                appendLine("dataSaver=${contextSummary.permissions.dataSaverState}")
                appendLine("powerSave=${contextSummary.environment.powerSaveModeState}")
                appendLine("networkMetered=${contextSummary.environment.networkMeteredState}")
                appendLine("roaming=${contextSummary.environment.roamingState}")
            }
            telemetry.firstOrNull()?.let { sample ->
                appendLine("networkType=${sample.networkType}")
                appendLine("failureClass=${sample.failureClass ?: "none"}")
                appendLine("lastFailureClass=${sample.lastFailureClass ?: \"none\"}")
                appendLine("lastFallbackAction=${sample.lastFallbackAction ?: \"none\"}")
                appendLine("winningStrategyFamily=${sample.winningStrategyFamily() ?: "none"}")
                appendLine("telemetryNetworkFingerprintHash=${sample.telemetryNetworkFingerprintHash ?: "none"}")
                appendLine("rttBand=${sample.rttBand()}")
                appendLine("retryCount=${sample.retryCount()}")
                appendLine("resolverId=${sample.resolverId ?: "unknown"}")
                appendLine("resolverProtocol=${sample.resolverProtocol ?: "unknown"}")
                appendLine("resolverEndpoint=${sample.resolverEndpoint ?: "unknown"}")
                appendLine("resolverLatencyMs=${sample.resolverLatencyMs ?: 0}")
                appendLine("dnsFailuresTotal=${sample.dnsFailuresTotal}")
                appendLine("resolverFallbackReason=${sample.resolverFallbackReason ?: "none"}")
                appendLine("networkHandoverClass=${sample.networkHandoverClass ?: "none"}")
                appendLine("txBytes=${sample.txBytes}")
                appendLine("rxBytes=${sample.rxBytes}")
            }
            appendLine("resultCount=${results.size}")
            results.take(5).forEach { result ->
                appendLine("${result.probeType}:${result.target}=${result.outcome}")
            }
            if (globalEvents.isNotEmpty()) {
                appendLine("recentWarnings=")
                globalEvents
                    .filter {
                        it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
                    }.take(3)
                    .forEach { warning ->
                        appendLine("- ${warning.source}: ${warning.message}")
                    }
            }
        }.trim()

    private fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
        buildString {
            appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
            results.forEach { result ->
                appendLine(
                    listOf(
                        csvField(result.sessionId),
                        csvField(result.probeType),
                        csvField(result.target),
                        csvField(result.outcome),
                        csvField(result.probeRetryCount(json)?.toString().orEmpty()),
                        csvField(result.createdAt),
                        csvField(result.detailJson),
                    ).joinToString(","),
                )
            }
        }

    private fun buildNativeEventsCsv(
        primaryEvents: List<NativeSessionEventEntity>,
        globalEvents: List<NativeSessionEventEntity>,
    ): String =
        buildString {
            appendLine("scope,sessionId,source,level,message,createdAt")
            primaryEvents.forEach { event ->
                appendLine(
                    listOf(
                        csvField("session"),
                        csvField(event.sessionId.orEmpty()),
                        csvField(event.source),
                        csvField(event.level),
                        csvField(event.message),
                        csvField(event.createdAt),
                    ).joinToString(","),
                )
            }
            globalEvents.forEach { event ->
                appendLine(
                    listOf(
                        csvField("global"),
                        csvField(event.sessionId.orEmpty()),
                        csvField(event.source),
                        csvField(event.level),
                        csvField(event.message),
                        csvField(event.createdAt),
                    ).joinToString(","),
                )
            }
        }

    private fun csvField(value: Any?): String =
        buildString {
            append('"')
            append(value?.toString().orEmpty().replace("\"", "\"\""))
            append('"')
        }

    private fun ProbeResultEntity.probeRetryCount(json: Json): Int? =
        runCatching {
            json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
        }.getOrNull()?.let(::deriveProbeRetryCount)

    private suspend fun pollScanResult(
        sessionId: String,
        bridge: NetworkDiagnosticsBridge,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ) {
        while (true) {
            persistNativeEvents(
                sessionId = sessionId,
                payload = bridge.pollPassiveEventsJson(),
            )
            val progress =
                bridge.pollProgressJson()
                    ?.let { json.decodeFromString(ScanProgress.serializer(), it) }
            _activeScanProgress.value = progress
            if (progress != null && progress.isFinished) {
                val report =
                    awaitFinishedReportJson(bridge)
                        ?.let { json.decodeFromString(ScanReport.serializer(), it) }
                        ?: throw IllegalStateException("Diagnostics scan completed without a report")
                val enrichedReport = enrichScanReport(report, settings)
                val finalReport = maybeApplyTemporaryResolverOverride(enrichedReport, settings)
                persistScanReport(finalReport)
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
                persistNativeEvents(
                    sessionId = sessionId,
                    payload = bridge.pollPassiveEventsJson(),
                )
                _activeScanProgress.value = null
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
            if (report.strategyProbeReport == null) {
                computeResolverRecommendation(report, settings)
            } else {
                null
            }
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

    private fun computeResolverRecommendation(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): ResolverRecommendation? {
        val dnsResults = report.results.filter { it.probeType == "dns_integrity" }
        if (dnsResults.isEmpty()) {
            return null
        }
        val triggerOutcome =
            dnsResults
                .firstOrNull { it.outcome == "dns_substitution" || it.outcome == "udp_blocked" }
                ?.outcome
                ?: return null

        val builtInProviders = BuiltInDnsProviders.associateBy { it.providerId }
        data class Candidate(
            val providerId: String,
            val protocol: String,
            val endpoint: String,
            val bootstrapIps: List<String>,
            val matchCount: Int,
            val averageLatencyMs: Long?,
        )

        val candidates =
            dnsResults
                .mapNotNull { result ->
                    val details = result.details.associate { it.key to it.value }
                    val providerId = details["encryptedResolverId"].orEmpty()
                    val provider = builtInProviders[providerId] ?: return@mapNotNull null
                    providerId to (result to details)
                }.groupBy({ it.first }, { it.second })
                .mapNotNull { (providerId, entries) ->
                    val provider = builtInProviders[providerId] ?: return@mapNotNull null
                    val matchEntries = entries.filter { it.first.outcome == "dns_match" }
                    if (matchEntries.isEmpty()) {
                        return@mapNotNull null
                    }
                    Candidate(
                        providerId = providerId,
                        protocol = matchEntries.first().second["encryptedProtocol"].orEmpty().ifBlank {
                            provider.protocol
                        },
                        endpoint = matchEntries.first().second["encryptedEndpoint"].orEmpty().ifBlank {
                            provider.dohUrl ?: "${provider.host}:${provider.port}"
                        },
                        bootstrapIps =
                            matchEntries.first().second["encryptedBootstrapIps"]
                                ?.split('|')
                                ?.filter { it.isNotBlank() }
                                ?.takeIf { it.isNotEmpty() }
                                ?: provider.bootstrapIps,
                        matchCount = matchEntries.size,
                        averageLatencyMs =
                            matchEntries
                                .mapNotNull { (_, details) -> details["encryptedLatencyMs"]?.toLongOrNull() }
                                .takeIf { it.isNotEmpty() }
                                ?.average()
                                ?.toLong(),
                    )
                }
        if (candidates.isEmpty()) {
            return null
        }

        val currentProvider = settings.activeDnsSettings().providerId
        val selected =
            candidates.minWithOrNull(
                compareBy<Candidate>(
                    { -it.matchCount },
                    { it.averageLatencyMs ?: Long.MAX_VALUE },
                    { if (it.providerId == currentProvider) 0 else 1 },
                    { if (it.providerId == DnsProviderCloudflare) 0 else 1 },
                ),
            ) ?: return null

        val provider = builtInProviders.getValue(selected.providerId)
        return ResolverRecommendation(
            triggerOutcome = triggerOutcome,
            selectedResolverId = selected.providerId,
            selectedProtocol = selected.protocol,
            selectedEndpoint = selected.endpoint,
            selectedBootstrapIps = selected.bootstrapIps,
            rationale =
                "UDP DNS showed $triggerOutcome while ${provider.displayName} returned matching encrypted answers " +
                    "on ${selected.matchCount} probe(s) with ${selected.averageLatencyMs ?: 0} ms average latency.",
            appliedTemporarily = false,
            persistable = true,
        )
    }

    private fun expandConnectivityDnsTargets(targets: List<DnsTarget>): List<DnsTarget> =
        targets.flatMap { target ->
            if (
                target.encryptedResolverId != null ||
                target.encryptedProtocol != null ||
                target.encryptedHost != null ||
                target.encryptedDohUrl != null ||
                target.encryptedBootstrapIps.isNotEmpty()
            ) {
                listOf(target)
            } else {
                BuiltInDnsProviders.map { provider ->
                    target.copy(
                        encryptedResolverId = provider.providerId,
                        encryptedProtocol = EncryptedDnsProtocolDoh,
                        encryptedHost = provider.host,
                        encryptedPort = provider.port,
                        encryptedTlsServerName = provider.tlsServerName,
                        encryptedBootstrapIps = provider.bootstrapIps,
                        encryptedDohUrl = provider.dohUrl,
                    )
                }
            }
        }

    private suspend fun loadResolverRecommendation(sessionId: String): ResolverRecommendation? =
        historyRepository.getScanSession(sessionId)?.reportJson?.let(::decodeScanReport)?.resolverRecommendation

    private fun installTemporaryResolverOverride(recommendation: ResolverRecommendation) {
        val provider =
            BuiltInDnsProviders.firstOrNull { it.providerId == recommendation.selectedResolverId }
                ?: return
        resolverOverrideStore.setTemporaryOverride(
            TemporaryResolverOverride(
                resolverId = provider.providerId,
                protocol = provider.protocol,
                host = provider.host,
                port = provider.port,
                tlsServerName = provider.tlsServerName,
                bootstrapIps = provider.bootstrapIps,
                dohUrl = provider.dohUrl.orEmpty(),
                dnscryptProviderName = provider.dnscryptProviderName.orEmpty(),
                dnscryptPublicKey = provider.dnscryptPublicKey.orEmpty(),
                reason = recommendation.rationale,
                appliedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistScanFailure(
        sessionId: String,
        summary: String,
    ) {
        val existing = historyRepository.getScanSession(sessionId) ?: return
        historyRepository.upsertScanSession(
            existing.copy(
                status = "failed",
                summary = summary,
                finishedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistScanReport(report: ScanReport) {
        val normalizedReport =
            report.copy(
                results = report.results.map { result -> result.withDerivedProbeRetryCount() },
            )
        val existing = historyRepository.getScanSession(report.sessionId)
        historyRepository.upsertScanSession(
            ScanSessionEntity(
                id = normalizedReport.sessionId,
                profileId = normalizedReport.profileId,
                approachProfileId = existing?.approachProfileId,
                approachProfileName = existing?.approachProfileName,
                strategyId = existing?.strategyId,
                strategyLabel = existing?.strategyLabel,
                strategyJson = existing?.strategyJson,
                pathMode = normalizedReport.pathMode.name,
                serviceMode = serviceStateStore.status.value.second.name,
                status = "completed",
                summary = normalizedReport.summary,
                reportJson = json.encodeToString(ScanReport.serializer(), normalizedReport),
                startedAt = normalizedReport.startedAt,
                finishedAt = normalizedReport.finishedAt,
            ),
        )
        historyRepository.replaceProbeResults(
            normalizedReport.sessionId,
            normalizedReport.results.map { result ->
                ProbeResultEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = normalizedReport.sessionId,
                    probeType = result.probeType,
                    target = result.target,
                    outcome = result.outcome,
                    detailJson = json.encodeToString(ListSerializer(ProbeDetail.serializer()), result.details),
                    createdAt = normalizedReport.finishedAt,
                )
            },
        )
        bridgeEventsToHistory(normalizedReport)
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

    private suspend fun persistNativeEvents(
        sessionId: String,
        payload: String?,
    ) {
        val events =
            payload
                ?.takeIf { it.isNotBlank() && it != "[]" }
                ?.let { json.decodeFromString(ListSerializer(NativeSessionEvent.serializer()), it) }
                .orEmpty()
        events.forEach { event ->
            historyRepository.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    source = event.source,
                    level = event.level,
                    message = event.message,
                    createdAt = event.createdAt,
                ),
            )
        }
    }

    private suspend fun bridgeEventsToHistory(report: ScanReport) {
        report.results.forEach { result ->
            historyRepository.insertNativeSessionEvent(
                NativeSessionEventEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = report.sessionId,
                    source = result.probeType,
                    level = if (result.outcome.contains("ok", ignoreCase = true)) "info" else "warn",
                    message = "${result.target}: ${result.outcome}",
                    createdAt = report.finishedAt,
                ),
            )
        }
    }

    internal suspend fun persistServiceNativeEvents(serviceTelemetry: com.poyka.ripdpi.services.ServiceTelemetrySnapshot) {
        (serviceTelemetry.proxyTelemetry.nativeEvents + serviceTelemetry.tunnelTelemetry.nativeEvents)
            .forEach { event ->
                historyRepository.insertNativeSessionEvent(
                    NativeSessionEventEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = null,
                        source = event.source,
                        level = event.level,
                        message = event.message,
                        createdAt = event.createdAt,
                    ),
                )
            }
    }

    private fun decodeScanReport(payload: String?): ScanReport? =
        payload?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(ScanReport.serializer(), it) }.getOrNull()
        }

    private fun decodeStrategySignature(payload: String?): BypassStrategySignature? =
        payload?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(BypassStrategySignature.serializer(), it) }.getOrNull()
        }

    private fun buildApproachSummaries(
        scanSessions: List<ScanSessionEntity>,
        usageSessions: List<BypassUsageSessionEntity>,
    ): List<BypassApproachSummary> {
        val profileIds =
            (
                scanSessions.mapNotNull { it.approachProfileId ?: it.profileId.takeIf { value -> value.isNotBlank() } } +
                    usageSessions.mapNotNull { it.approachProfileId }
            ).distinct()
        val strategyIds =
            (
                scanSessions.mapNotNull { it.strategyId } +
                    usageSessions.map { it.strategyId }
            ).distinct()

        val profileSummaries =
            profileIds.map { profileId ->
                val matchingSessions =
                    scanSessions.filter { session ->
                        session.approachProfileId == profileId || session.profileId == profileId
                    }
                val matchingUsage = usageSessions.filter { it.approachProfileId == profileId }
                aggregateApproachSummary(
                    kind = BypassApproachKind.Profile,
                    id = profileId,
                    displayName =
                        matchingSessions.firstNotNullOfOrNull { it.approachProfileName }
                            ?: matchingUsage.firstNotNullOfOrNull { it.approachProfileName }
                            ?: profileId,
                    secondaryLabel = "Profile",
                    matchingSessions = matchingSessions,
                    matchingUsage = matchingUsage,
                )
            }

        val strategySummaries =
            strategyIds.map { strategyId ->
                val matchingSessions = scanSessions.filter { it.strategyId == strategyId }
                val matchingUsage = usageSessions.filter { it.strategyId == strategyId }
                aggregateApproachSummary(
                    kind = BypassApproachKind.Strategy,
                    id = strategyId,
                    displayName =
                        matchingSessions.firstNotNullOfOrNull { it.strategyLabel }
                            ?: matchingUsage.firstOrNull()?.strategyLabel
                            ?: strategyId,
                    secondaryLabel = "Strategy",
                    matchingSessions = matchingSessions,
                    matchingUsage = matchingUsage,
                )
            }

        return (profileSummaries + strategySummaries)
            .sortedWith(
                compareByDescending<BypassApproachSummary> { it.validatedSuccessRate ?: -1f }
                    .thenByDescending { it.validatedScanCount }
                    .thenByDescending { it.usageCount }
                    .thenByDescending { it.lastUsedAt ?: 0L },
            )
    }

    private fun aggregateApproachSummary(
        kind: BypassApproachKind,
        id: String,
        displayName: String,
        secondaryLabel: String,
        matchingSessions: List<ScanSessionEntity>,
        matchingUsage: List<BypassUsageSessionEntity>,
    ): BypassApproachSummary {
        val validatedReports =
            matchingSessions
                .mapNotNull { session -> decodeScanReport(session.reportJson)?.let { session to it } }
        val successfulReports = validatedReports.count { (_, report) -> report.results.isNotEmpty() && report.results.all { it.outcome.isSuccessfulOutcome() } }
        val allResults = validatedReports.flatMap { it.second.results }
        val failureOutcomes =
            allResults
                .filterNot { it.outcome.isSuccessfulOutcome() }
                .groupingBy { it.outcome }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { "${it.key} (${it.value})" }
                .take(3)
        val outcomeBreakdown =
            allResults
                .groupBy { it.probeType }
                .map { (probeType, results) ->
                    val failures = results.filterNot { it.outcome.isSuccessfulOutcome() }
                    BypassOutcomeBreakdown(
                        probeType = probeType,
                        successCount = results.count { it.outcome.isSuccessfulOutcome() },
                        warningCount = results.count { it.outcome.isWarningOutcome() },
                        failureCount = failures.size,
                        dominantFailureOutcome =
                            failures
                                .groupingBy { it.outcome }
                                .eachCount()
                                .maxByOrNull { it.value }
                                ?.key,
                    )
                }.sortedBy { it.probeType }
        val totalRuntimeDurationMs =
            matchingUsage.sumOf { usage ->
                (usage.finishedAt ?: System.currentTimeMillis()) - usage.startedAt
            }
        val recentUsage = matchingUsage.sortedByDescending { it.startedAt }.take(5)
        val latestValidated = validatedReports.maxByOrNull { it.first.startedAt }?.first
        val verificationState = if (validatedReports.isEmpty()) "unverified" else "validated"

        return BypassApproachSummary(
            approachId = BypassApproachId(kind = kind, value = id),
            displayName = displayName,
            secondaryLabel = secondaryLabel,
            verificationState = verificationState,
            validatedScanCount = validatedReports.size,
            validatedSuccessCount = successfulReports,
            validatedSuccessRate =
                validatedReports.size
                    .takeIf { it > 0 }
                    ?.let { successfulReports.toFloat() / it.toFloat() },
            lastValidatedResult = latestValidated?.summary,
            usageCount = matchingUsage.size,
            totalRuntimeDurationMs = totalRuntimeDurationMs,
            recentRuntimeHealth =
                BypassRuntimeHealthSummary(
                    totalErrors = recentUsage.sumOf { it.totalErrors },
                    routeChanges = recentUsage.sumOf { it.routeChanges },
                    restartCount = recentUsage.maxOfOrNull { it.restartCount } ?: 0,
                    lastEndedReason = recentUsage.firstOrNull { !it.endedReason.isNullOrBlank() }?.endedReason,
                ),
            lastUsedAt = matchingUsage.maxOfOrNull { it.finishedAt ?: it.startedAt },
            topFailureOutcomes = failureOutcomes,
            outcomeBreakdown = outcomeBreakdown,
        )
    }
}

@Serializable
internal data class DiagnosticsArchivePayload(
    val schemaVersion: Int,
    val scope: String,
    val privacyMode: String,
    val session: ScanSessionEntity?,
    val results: List<ProbeResultEntity>,
    val sessionSnapshots: List<NetworkSnapshotEntity>,
    val sessionContexts: List<DiagnosticContextEntity>,
    val sessionEvents: List<NativeSessionEventEntity>,
    val latestPassiveSnapshot: NetworkSnapshotEntity?,
    val latestPassiveContext: DiagnosticContextEntity?,
    val telemetry: List<TelemetrySampleEntity>,
    val globalEvents: List<NativeSessionEventEntity>,
    val approachSummaries: List<BypassApproachSummary>,
)

@Serializable
internal data class StrategyMatrixArchivePayload(
    val sessionId: String?,
    val profileId: String?,
    val strategyProbeReport: StrategyProbeReport? = null,
)

@Serializable
internal data class DiagnosticsArchiveSnapshotPayload(
    val sessionSnapshots: List<NetworkSnapshotEntity>,
    val latestPassiveSnapshot: NetworkSnapshotEntity?,
)

@Serializable
internal data class DiagnosticsArchiveContextPayload(
    val sessionContexts: List<DiagnosticContextEntity>,
    val latestPassiveContext: DiagnosticContextEntity?,
)

private fun TelemetrySampleEntity.toArchiveTelemetrySummary(): ArchiveTelemetrySummary =
    ArchiveTelemetrySummary(
        failureClass = failureClass,
        telemetryNetworkFingerprintHash = telemetryNetworkFingerprintHash,
        winningTcpStrategyFamily = winningTcpStrategyFamily,
        winningQuicStrategyFamily = winningQuicStrategyFamily,
        winningStrategyFamily = winningStrategyFamily(),
        proxyRttBand = proxyRttBand,
        resolverRttBand = resolverRttBand,
        rttBand = rttBand(),
        proxyRouteRetryCount = proxyRouteRetryCount,
        tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
        retryCount = retryCount(),
    )

        lastFailureClass = lastFailureClass,
        lastFallbackAction = lastFallbackAction,
@Serializable
internal data class DiagnosticsArchiveManifest(
    val fileName: String,
    val createdAt: Long,
    val schemaVersion: Int,
    val privacyMode: String,
    val scope: String,
    val includedSessionId: String?,
    val sessionResultCount: Int,
    val sessionSnapshotCount: Int,
    val contextSnapshotCount: Int,
    val sessionEventCount: Int,
    val telemetrySampleCount: Int,
    val globalEventCount: Int,
    val approachCount: Int,
    val selectedApproach: BypassApproachSummary?,
    val networkSummary: RedactedNetworkSummary?,
    val contextSummary: RedactedDiagnosticContextSummary?,
    val latestTelemetrySummary: ArchiveTelemetrySummary? = null,
    val includedFiles: List<String>,
    val logcatIncluded: Boolean,
    val logcatCaptureScope: String,
    val logcatByteCount: Int,
)

@Serializable
internal data class ArchiveTelemetrySummary(
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val winningStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val rttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val retryCount: Long = 0,
)

    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
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

private fun NetworkSnapshotModel.toRedactedSummary(): RedactedNetworkSummary =
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

private fun DiagnosticContextModel.toRedactedSummary(): RedactedDiagnosticContextSummary =
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

private fun String.isSuccessfulOutcome(): Boolean {
    val normalized = lowercase(Locale.US)
    return normalized.contains("ok") ||
        normalized.contains("success") ||
        normalized.contains("completed") ||
        normalized.contains("reachable") ||
        normalized.contains("allowed")
}

private fun String.isWarningOutcome(): Boolean {
    if (isSuccessfulOutcome()) {
        return false
    }
    val normalized = lowercase(Locale.US)
    return normalized.contains("timeout") ||
        normalized.contains("partial") ||
        normalized.contains("mixed") ||
        normalized.contains("warn")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsManagerModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsManager(
        manager: DefaultDiagnosticsManager,
    ): DiagnosticsManager
}
