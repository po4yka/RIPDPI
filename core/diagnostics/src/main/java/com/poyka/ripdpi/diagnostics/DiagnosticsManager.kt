package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
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
    val snapshots: Flow<List<NetworkSnapshotEntity>>
    val telemetry: Flow<List<TelemetrySampleEntity>>
    val nativeEvents: Flow<List<NativeSessionEventEntity>>
    val exports: Flow<List<ExportRecordEntity>>

    suspend fun initialize()

    suspend fun startScan(pathMode: ScanPathMode): String

    suspend fun cancelActiveScan()

    suspend fun exportBundle(sessionId: String?): ExportBundle
}

@Singleton
class DefaultDiagnosticsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val historyRepository: DiagnosticsHistoryRepository,
        private val networkMetadataProvider: NetworkMetadataProvider,
        private val networkDiagnosticsBridgeFactory: NetworkDiagnosticsBridgeFactory,
        private val runtimeCoordinator: DiagnosticsRuntimeCoordinator,
        private val serviceStateStore: ServiceStateStore,
    ) : DiagnosticsManager {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeScanProgress = MutableStateFlow<ScanProgress?>(null)
    override val activeScanProgress: StateFlow<ScanProgress?> = _activeScanProgress.asStateFlow()
    override val profiles: Flow<List<DiagnosticProfileEntity>> = historyRepository.observeProfiles()
    override val sessions: Flow<List<ScanSessionEntity>> = historyRepository.observeRecentScanSessions()
    override val snapshots: Flow<List<NetworkSnapshotEntity>> = historyRepository.observeSnapshots()
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
        importBundledProfiles()
        startPassiveMonitor()
    }

    override suspend fun startScan(pathMode: ScanPathMode): String {
        val settings = appSettingsRepository.snapshot()
        val profileId = settings.diagnosticsActiveProfileId.ifEmpty { "default" }
        val profile = requireNotNull(historyRepository.getProfile(profileId)) { "Unknown diagnostics profile: $profileId" }
        val request = json.decodeFromString(ScanRequest.serializer(), profile.requestJson)
        val sessionId = UUID.randomUUID().toString()
        val serviceMode = serviceStateStore.status.value.second.name
        historyRepository.upsertScanSession(
            ScanSessionEntity(
                id = sessionId,
                profileId = profileId,
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

        val bridge = networkDiagnosticsBridgeFactory.create().also { activeDiagnosticsBridge = it }
        val requestForPath =
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
        bridge.startScan(
            requestJson = json.encodeToString(ScanRequest.serializer(), requestForPath),
            sessionId = sessionId,
        )
        _activeScanProgress.value =
            ScanProgress(
                sessionId = sessionId,
                phase = "preparing",
                completedSteps = 0,
                totalSteps = 1,
                message = "Preparing diagnostics session",
            )

        scope.launch {
            val scanBlock: suspend () -> Unit = {
                try {
                    pollScanResult(sessionId, bridge)
                } finally {
                    bridge.destroy()
                    if (activeDiagnosticsBridge === bridge) {
                        activeDiagnosticsBridge = null
                    }
                }
            }

            when (pathMode) {
                ScanPathMode.RAW_PATH -> runtimeCoordinator.runRawPathScan(scanBlock)
                ScanPathMode.IN_PATH -> scanBlock()
            }
        }
        return sessionId
    }

    override suspend fun cancelActiveScan() {
        activeDiagnosticsBridge?.cancelScan()
        _activeScanProgress.value = null
    }

    override suspend fun exportBundle(sessionId: String?): ExportBundle =
        withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis()
            val fileName = "ripdpi-diagnostics-$timestamp.zip"
            val target = File(context.cacheDir, fileName)
            val sessions = historyRepository.observeRecentScanSessions(limit = 50).first()
            val snapshots = historyRepository.observeSnapshots(limit = 200).first()
            val telemetry = historyRepository.observeTelemetry(limit = 500).first()
            val events = historyRepository.observeNativeEvents(limit = 500).first()
            val selectedSession = sessionId?.let { historyRepository.getScanSession(it) }
            val selectedResults = sessionId?.let { historyRepository.getProbeResults(it) }.orEmpty()
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("summary.txt"))
                val summary =
                    buildString {
                        appendLine("RIPDPI diagnostics export")
                        appendLine("generatedAt=$timestamp")
                        appendLine("selectedSession=${selectedSession?.id ?: "all"}")
                        appendLine("sessions=${sessions.size}")
                        appendLine("snapshots=${snapshots.size}")
                        appendLine("telemetry=${telemetry.size}")
                        appendLine("events=${events.size}")
                    }
                zip.write(summary.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("report.json"))
                val reportJson =
                    json.encodeToString(
                        ExportPayload.serializer(),
                        ExportPayload(
                            session = selectedSession,
                            results = selectedResults,
                            snapshots = snapshots,
                            telemetry = telemetry,
                            events = events,
                        ),
                    )
                zip.write(reportJson.toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("telemetry.csv"))
                val csv =
                    buildString {
                        appendLine("createdAt,activeMode,connectionState,networkType,publicIp,txPackets,txBytes,rxPackets,rxBytes")
                        telemetry.forEach { sample ->
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
                        ExportManifest.serializer(),
                        ExportManifest(
                            fileName = fileName,
                            createdAt = timestamp,
                            includedSessionId = selectedSession?.id,
                        ),
                    )
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            }
            historyRepository.insertExportRecord(
                ExportRecordEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    uri = target.absolutePath,
                    fileName = fileName,
                    createdAt = timestamp,
                ),
            )
            ExportBundle(fileName = fileName, absolutePath = target.absolutePath)
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

    private fun startPassiveMonitor() {
        scope.launch {
            while (true) {
                val settings = appSettingsRepository.snapshot()
                if (settings.diagnosticsMonitorEnabled && serviceStateStore.status.value.first.name == "Running") {
                    val snapshot = networkMetadataProvider.captureSnapshot()
                    val serviceTelemetry = serviceStateStore.telemetry.value
                    val tunnelStats = serviceTelemetry.tunnelStats
                    historyRepository.upsertSnapshot(
                        NetworkSnapshotEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            snapshotKind = "passive",
                            payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), snapshot),
                            capturedAt = snapshot.capturedAt,
                        ),
                    )
                    historyRepository.insertTelemetrySample(
                        TelemetrySampleEntity(
                            id = UUID.randomUUID().toString(),
                            sessionId = null,
                            activeMode = serviceStateStore.status.value.second.name,
                            connectionState = serviceStateStore.status.value.first.name,
                            networkType = snapshot.transport,
                            publicIp = snapshot.publicIp,
                            txPackets = tunnelStats.txPackets,
                            txBytes = tunnelStats.txBytes,
                            rxPackets = tunnelStats.rxPackets,
                            rxBytes = tunnelStats.rxBytes,
                            createdAt = snapshot.capturedAt,
                        ),
                    )
                    persistServiceNativeEvents(serviceTelemetry)
                    historyRepository.trimOldData(settings.diagnosticsHistoryRetentionDays)
                }
                delay(settingsDelaySeconds(settings = appSettingsRepository.snapshot()) * 1_000L)
            }
        }
    }

    private fun settingsDelaySeconds(settings: com.poyka.ripdpi.proto.AppSettings): Int =
        settings.diagnosticsSampleIntervalSeconds.coerceIn(5, 300)

    private suspend fun pollScanResult(
        sessionId: String,
        bridge: NetworkDiagnosticsBridge,
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
                        ?: break
                persistScanReport(report)
                historyRepository.upsertSnapshot(
                    NetworkSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        snapshotKind = "post_scan",
                        payloadJson = json.encodeToString(NetworkSnapshotModel.serializer(), networkMetadataProvider.captureSnapshot()),
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

    private suspend fun persistScanReport(report: ScanReport) {
        historyRepository.upsertScanSession(
            ScanSessionEntity(
                id = report.sessionId,
                profileId = report.profileId,
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

    private suspend fun persistServiceNativeEvents(serviceTelemetry: com.poyka.ripdpi.services.ServiceTelemetrySnapshot) {
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
}

@Serializable
private data class ExportPayload(
    val session: ScanSessionEntity?,
    val results: List<ProbeResultEntity>,
    val snapshots: List<NetworkSnapshotEntity>,
    val telemetry: List<TelemetrySampleEntity>,
    val events: List<NativeSessionEventEntity>,
)

@Serializable
private data class ExportManifest(
    val fileName: String,
    val createdAt: Long,
    val includedSessionId: String?,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsManagerModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsManager(
        manager: DefaultDiagnosticsManager,
    ): DiagnosticsManager
}
