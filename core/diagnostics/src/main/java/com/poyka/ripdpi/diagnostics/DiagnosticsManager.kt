package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.core.resolveHostAutolearnStorePath
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.services.DiagnosticsRuntimeCoordinator
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
}

@Singleton
class DefaultDiagnosticsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val historyRepository: DiagnosticsHistoryRepository,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val diagnosticsContextProvider: DiagnosticsContextProvider,
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
        private val serviceStateStore: ServiceStateStore,
    ) : DiagnosticsManager {
        private companion object {
        private const val DiagnosticsArchiveDirectory = "diagnostics-archives"
        private const val DiagnosticsArchivePrefix = "ripdpi-diagnostics-"
        private const val ArchiveSchemaVersion = 5
        private const val ArchivePrivacyMode = "split_output"
        private const val ArchiveScopeHybrid = "hybrid"
        private const val MaxArchiveFiles = 5
        private const val MaxArchiveAgeMs = 3L * 24L * 60L * 60L * 1000L
        private const val ArchiveTelemetryLimit = 120
        private const val ArchiveGlobalEventLimit = 80
        private const val ArchiveSnapshotLimit = 250
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
            val approachSummaries = buildApproachSummaries(scanSessions = sessions, usageSessions = usageSessions)
            val primarySession =
                sessionId
                    ?.let { historyRepository.getScanSession(it) }
                    ?: sessions.firstOrNull { it.reportJson != null }
                    ?: sessions.firstOrNull()
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
                    )
                zip.write(summary.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("report.json"))
                val reportJson = json.encodeToString(DiagnosticsArchivePayload.serializer(), payload)
                zip.write(reportJson.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("telemetry.csv"))
                val csv =
                    buildString {
                        appendLine("createdAt,activeMode,connectionState,networkType,publicIp,txPackets,txBytes,rxPackets,rxBytes")
                        payload.telemetry.forEach { sample ->
                            appendLine(
                                listOf(
                                    sample.createdAt,
                                    sample.activeMode.orEmpty(),
                                    sample.connectionState,
                                    sample.networkType,
                                    sample.publicIp.orEmpty(),
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
    ): String =
        buildString {
            appendLine("RIPDPI diagnostics archive")
            appendLine("generatedAt=$createdAt")
            appendLine("scope=$ArchiveScopeHybrid")
            appendLine("privacyMode=$ArchivePrivacyMode")
            appendLine("selectedSession=${session?.id ?: "latest-live"}")
            session?.let {
                appendLine("pathMode=${it.pathMode}")
                appendLine("serviceMode=${it.serviceMode ?: "unknown"}")
                appendLine("status=${it.status}")
                appendLine("summary=${it.summary}")
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
                    bridge.takeReportJson()
                        ?.let { json.decodeFromString(ScanReport.serializer(), it) }
                        ?: throw IllegalStateException("Diagnostics scan completed without a report")
                persistScanReport(enrichScanReport(report, settings))
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

    private fun enrichScanReport(
        report: ScanReport,
        settings: com.poyka.ripdpi.proto.AppSettings,
    ): ScanReport {
        val strategyProbe = report.strategyProbeReport ?: return report
        val recommendation = strategyProbe.recommendation
        val strategySignature =
            decodeRipDpiProxyUiPreferences(recommendation.recommendedProxyConfigJson)
                ?.let { preferences ->
                    deriveBypassStrategySignature(
                        preferences = preferences,
                        routeGroup = null,
                        modeOverride = Mode.fromString(settings.ripdpiMode.ifEmpty { "vpn" }),
                    )
                }

        return report.copy(
            strategyProbeReport =
                strategyProbe.copy(
                    recommendation = recommendation.copy(strategySignature = strategySignature),
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
        val existing = historyRepository.getScanSession(report.sessionId)
        historyRepository.upsertScanSession(
            ScanSessionEntity(
                id = report.sessionId,
                profileId = report.profileId,
                approachProfileId = existing?.approachProfileId,
                approachProfileName = existing?.approachProfileName,
                strategyId = existing?.strategyId,
                strategyLabel = existing?.strategyLabel,
                strategyJson = existing?.strategyJson,
                pathMode = report.pathMode.name,
                serviceMode = serviceStateStore.status.value.second.name,
                status = "completed",
                summary = report.summary,
                reportJson = json.encodeToString(ScanReport.serializer(), report),
                startedAt = report.startedAt,
                finishedAt = report.finishedAt,
            ),
        )
        historyRepository.replaceProbeResults(
            report.sessionId,
            report.results.map { result ->
                ProbeResultEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = report.sessionId,
                    probeType = result.probeType,
                    target = result.target,
                    outcome = result.outcome,
                    detailJson = json.encodeToString(ListSerializer(ProbeDetail.serializer()), result.details),
                    createdAt = report.finishedAt,
                )
            },
        )
        bridgeEventsToHistory(report)
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
)

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
