package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Suppress("LongMethod", "CyclomaticComplexMethod", "TooManyFunctions", "LargeClass")
internal object DiagnosticsArchiveBuilder {

    private const val DiagnosticsArchiveDirectory = "diagnostics-archives"
    private const val DiagnosticsArchivePrefix = "ripdpi-diagnostics-"
    private const val ArchiveSchemaVersion = 7
    private const val ArchivePrivacyMode = "split_output"
    private const val ArchiveScopeHybrid = "hybrid"
    private const val MaxArchiveFiles = 5
    private const val MaxArchiveAgeMs = 3L * 24L * 60L * 60L * 1000L
    private const val ArchiveTelemetryLimit = 120
    private const val ArchiveGlobalEventLimit = 80
    private const val ArchiveSnapshotLimit = 250

    fun cleanupCache(context: Context) {
        cleanupArchiveCache(context)
    }

    suspend fun build(
        sessionId: String?,
        context: Context,
        historyRepository: DiagnosticsHistoryRepository,
        logcatSnapshotCollector: LogcatSnapshotCollector,
        json: Json,
        approachSummariesProvider: suspend (List<ScanSessionEntity>, List<com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity>) -> List<BypassApproachSummary>,
        scanReportDecoder: (String?) -> ScanReport?,
    ): DiagnosticsArchive {
        cleanupArchiveCache(context)
        val archiveDirectory = ensureArchiveDirectory(context)
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
        val approachSummaries = approachSummariesProvider(sessions, usageSessions)
        val primarySession =
            sessionId
                ?.let { historyRepository.getScanSession(it) }
                ?: sessions.firstOrNull { it.reportJson != null }
                ?: sessions.firstOrNull()
        val primaryReport = scanReportDecoder(primarySession?.reportJson)
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
        writeZip(
            target = target,
            json = json,
            timestamp = timestamp,
            fileName = fileName,
            payload = payload,
            primarySession = primarySession,
            primaryReport = primaryReport,
            primaryResults = primaryResults,
            primarySnapshots = primarySnapshots,
            primaryContexts = primaryContexts,
            primaryEvents = primaryEvents,
            latestPassiveSnapshot = latestPassiveSnapshot,
            latestPassiveContext = latestPassiveContext,
            globalEvents = globalEvents,
            approachSummaries = approachSummaries,
            selectedApproachSummary = selectedApproachSummary,
            latestSnapshotModel = latestSnapshotModel,
            latestContextModel = latestContextModel,
            sessionContextModel = sessionContextModel,
            telemetry = telemetry,
            logcatSnapshot = logcatSnapshot,
            includedFiles = includedFiles,
            scanReportDecoder = scanReportDecoder,
        )
        historyRepository.insertExportRecord(
            ExportRecordEntity(
                id = UUID.randomUUID().toString(),
                sessionId = primarySession?.id,
                uri = target.absolutePath,
                fileName = fileName,
                createdAt = timestamp,
            ),
        )
        return DiagnosticsArchive(
            fileName = fileName,
            absolutePath = target.absolutePath,
            sessionId = primarySession?.id,
            createdAt = timestamp,
            scope = ArchiveScopeHybrid,
            schemaVersion = ArchiveSchemaVersion,
            privacyMode = ArchivePrivacyMode,
        )
    }

    @Suppress("LongParameterList")
    private fun writeZip(
        target: File,
        json: Json,
        timestamp: Long,
        fileName: String,
        payload: DiagnosticsArchivePayload,
        primarySession: ScanSessionEntity?,
        primaryReport: ScanReport?,
        primaryResults: List<ProbeResultEntity>,
        primarySnapshots: List<NetworkSnapshotEntity>,
        primaryContexts: List<DiagnosticContextEntity>,
        primaryEvents: List<NativeSessionEventEntity>,
        latestPassiveSnapshot: NetworkSnapshotEntity?,
        latestPassiveContext: DiagnosticContextEntity?,
        globalEvents: List<NativeSessionEventEntity>,
        approachSummaries: List<BypassApproachSummary>,
        selectedApproachSummary: BypassApproachSummary?,
        latestSnapshotModel: NetworkSnapshotModel?,
        latestContextModel: DiagnosticContextModel?,
        sessionContextModel: DiagnosticContextModel?,
        telemetry: List<TelemetrySampleEntity>,
        logcatSnapshot: LogcatSnapshot?,
        includedFiles: List<String>,
        scanReportDecoder: (String?) -> ScanReport?,
    ) {
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
                    scanReportDecoder = scanReportDecoder,
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
            zip.write(buildProbeResultsCsv(primaryResults, json).toByteArray())
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
            zip.write(buildTelemetryCsv(payload).toByteArray())
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
    }

    private fun ensureArchiveDirectory(context: Context): File =
        File(context.cacheDir, DiagnosticsArchiveDirectory).apply {
            mkdirs()
        }

    private fun cleanupArchiveCache(context: Context) {
        val archiveDirectory = ensureArchiveDirectory(context)
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

    @Suppress("CyclomaticComplexMethod")
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
        scanReportDecoder: (String?) -> ScanReport?,
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
                scanReportDecoder(it.reportJson)?.strategyProbeReport?.let { strategyProbe ->
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
                appendLine("lastFailureClass=${sample.lastFailureClass ?: "none"}")
                appendLine("lastFallbackAction=${sample.lastFallbackAction ?: "none"}")
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

    private fun buildTelemetryCsv(payload: DiagnosticsArchivePayload): String =
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

    private fun buildProbeResultsCsv(results: List<ProbeResultEntity>, json: Json): String =
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

internal fun TelemetrySampleEntity.toArchiveTelemetrySummary(): ArchiveTelemetrySummary =
    ArchiveTelemetrySummary(
        failureClass = failureClass,
        lastFailureClass = lastFailureClass,
        lastFallbackAction = lastFallbackAction,
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

@Serializable
internal data class ArchiveTelemetrySummary(
    val failureClass: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
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
