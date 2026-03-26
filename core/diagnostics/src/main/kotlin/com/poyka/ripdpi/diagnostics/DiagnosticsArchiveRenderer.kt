package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Named

private const val SuccessRatePercentScale = 100

class DiagnosticsArchiveRenderer
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        private val projector: DiagnosticsSummaryProjector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
        ): List<DiagnosticsArchiveEntry> {
            val entries = buildBaseEntries(target, selection).toMutableList()
            selection.logcatSnapshot?.let { snapshot ->
                entries += DiagnosticsArchiveEntry(name = "logcat.txt", bytes = snapshot.content.toByteArray())
            }
            entries +=
                DiagnosticsArchiveEntry(
                    name = "telemetry.csv",
                    bytes = buildTelemetryCsv(selection.payload).toByteArray(),
                )
            entries +=
                DiagnosticsArchiveEntry(
                    name = "manifest.json",
                    bytes = encodeManifest(target, selection).toByteArray(),
                )
            return entries
        }

        private fun buildBaseEntries(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
        ): List<DiagnosticsArchiveEntry> =
            listOf(
                textEntry(
                    name = "summary.txt",
                    content = buildSummary(createdAt = target.createdAt, selection = selection),
                ),
                jsonEntry(
                    name = "report.json",
                    serializer = DiagnosticsArchivePayload.serializer(),
                    value = buildRedactedPayload(selection),
                ),
                jsonEntry(
                    name = "strategy-matrix.json",
                    serializer = StrategyMatrixArchivePayload.serializer(),
                    value =
                        StrategyMatrixArchivePayload(
                            sessionId = selection.primarySession?.id,
                            profileId = selection.primarySession?.profileId,
                            strategyProbeReport = selection.primaryReport?.strategyProbeReport,
                        ),
                ),
                textEntry(
                    name = "probe-results.csv",
                    content = buildProbeResultsCsv(selection.primaryResults),
                ),
                textEntry(
                    name = "native-events.csv",
                    content = buildNativeEventsCsv(selection.primaryEvents, selection.globalEvents),
                ),
                jsonEntry(
                    name = "network-snapshots.json",
                    serializer = DiagnosticsArchiveSnapshotPayload.serializer(),
                    value = buildSnapshotPayload(selection),
                ),
                jsonEntry(
                    name = "diagnostic-context.json",
                    serializer = DiagnosticsArchiveContextPayload.serializer(),
                    value = buildContextPayload(selection),
                ),
            )

        private fun buildRedactedPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchivePayload =
            selection.payload.copy(
                primaryReport = selection.primaryReport,
                sessionSnapshots = selection.payload.sessionSnapshots.map(redactor::redact),
                sessionContexts = selection.payload.sessionContexts.map(redactor::redact),
                latestPassiveSnapshot = selection.payload.latestPassiveSnapshot?.let(redactor::redact),
                latestPassiveContext = selection.payload.latestPassiveContext?.let(redactor::redact),
                telemetry =
                    selection.payload.telemetry.map { sample ->
                        sample.copy(publicIp = if (sample.publicIp != null) "redacted" else null)
                    },
            )

        private fun buildSnapshotPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveSnapshotPayload =
            DiagnosticsArchiveSnapshotPayload(
                sessionSnapshots =
                    selection.primarySnapshots
                        .mapNotNull(redactor::decodeNetworkSnapshot)
                        .map(redactor::redact),
                latestPassiveSnapshot =
                    redactor
                        .decodeNetworkSnapshot(selection.latestPassiveSnapshot)
                        ?.let(redactor::redact),
            )

        private fun buildContextPayload(selection: DiagnosticsArchiveSelection): DiagnosticsArchiveContextPayload =
            DiagnosticsArchiveContextPayload(
                sessionContexts =
                    selection.primaryContexts
                        .mapNotNull(redactor::decodeDiagnosticContext)
                        .map(redactor::redact),
                latestPassiveContext =
                    redactor
                        .decodeDiagnosticContext(selection.latestPassiveContext)
                        ?.let(redactor::redact),
            )

        private fun textEntry(
            name: String,
            content: String,
        ): DiagnosticsArchiveEntry = DiagnosticsArchiveEntry(name = name, bytes = content.toByteArray())

        private fun <T> jsonEntry(
            name: String,
            serializer: kotlinx.serialization.KSerializer<T>,
            value: T,
        ): DiagnosticsArchiveEntry =
            DiagnosticsArchiveEntry(
                name = name,
                bytes = json.encodeToString(serializer, value).toByteArray(),
            )

        private fun encodeManifest(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
        ): String {
            val summaryDocument = buildSummaryDocument(selection)
            val allEvents = selection.primaryEvents + selection.globalEvents
            val runtimeId = allEvents.latestCorrelation { it.runtimeId }
            val mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode }
            val policySignature = allEvents.latestCorrelation { it.policySignature }
            val fingerprintHash =
                selection.payload.telemetry.firstOrNull()?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash }
            val recentWarnings = allEvents.recentWarningPreview()
            val manifest =
                DiagnosticsArchiveManifest(
                    fileName = target.fileName,
                    createdAt = target.createdAt,
                    schemaVersion = DiagnosticsArchiveFormat.schemaVersion,
                    privacyMode = DiagnosticsArchiveFormat.privacyMode,
                    scope = DiagnosticsArchiveFormat.scope,
                    includedSessionId = selection.primarySession?.id,
                    sessionResultCount = selection.primaryResults.size,
                    sessionSnapshotCount = selection.primarySnapshots.size,
                    contextSnapshotCount = selection.primaryContexts.size,
                    sessionEventCount = selection.primaryEvents.size,
                    telemetrySampleCount = selection.payload.telemetry.size,
                    globalEventCount = selection.globalEvents.size,
                    approachCount = selection.payload.approachSummaries.size,
                    selectedApproach = selection.selectedApproachSummary,
                    networkSummary = selection.latestSnapshotModel?.let(redactor::redact)?.toRedactedSummary(),
                    contextSummary =
                        (selection.sessionContextModel ?: selection.latestContextModel)
                            ?.let(redactor::redact)
                            ?.toRedactedSummary(),
                    latestTelemetrySummary =
                        selection.payload.telemetry
                            .firstOrNull()
                            ?.toArchiveTelemetrySummary(),
                    runtimeId = runtimeId,
                    mode = mode,
                    policySignature = policySignature,
                    fingerprintHash = fingerprintHash,
                    networkScope = fingerprintHash,
                    lastFailure = recentWarnings.firstOrNull(),
                    lifecycleMilestones = allEvents.lifecycleMilestones(),
                    recentNativeWarnings = recentWarnings,
                    classifierVersion = summaryDocument.classifierVersion,
                    diagnosisCount = summaryDocument.diagnoses.size,
                    packVersions = summaryDocument.packVersions,
                    includedFiles = selection.includedFiles,
                    logcatIncluded = selection.logcatSnapshot != null,
                    logcatCaptureScope = LogcatSnapshotCollector.AppVisibleSnapshotScope,
                    logcatByteCount = selection.logcatSnapshot?.byteCount ?: 0,
                )
            val manifestElement =
                json.encodeToJsonElement(DiagnosticsArchiveManifest.serializer(), manifest)
            val normalizedManifest =
                if (selection.selectedApproachSummary == null && manifestElement is JsonObject) {
                    JsonObject(manifestElement + ("selectedApproach" to JsonNull))
                } else {
                    manifestElement
                }
            return json.encodeToString(JsonElement.serializer(), normalizedManifest)
        }

        internal fun buildSummary(
            createdAt: Long,
            selection: DiagnosticsArchiveSelection,
        ): String {
            val summaryDocument = buildSummaryDocument(selection)
            val allEvents = selection.primaryEvents + selection.globalEvents
            val runtimeId = allEvents.latestCorrelation { it.runtimeId }
            val mode = selection.primarySession?.serviceMode ?: allEvents.latestCorrelation { it.mode }
            val policySignature = allEvents.latestCorrelation { it.policySignature }
            val fingerprintHash =
                selection.payload.telemetry.firstOrNull()?.telemetryNetworkFingerprintHash
                    ?: allEvents.latestCorrelation { it.fingerprintHash }
            val recentWarnings = allEvents.recentWarningPreview()
            return DiagnosticsSummaryTextRenderer.render(
                document = summaryDocument,
                preludeLines =
                    buildList {
                        add("RIPDPI diagnostics archive")
                        add("generatedAt=$createdAt")
                        add("scope=${DiagnosticsArchiveFormat.scope}")
                        add("privacyMode=${DiagnosticsArchiveFormat.privacyMode}")
                        add("logcatIncluded=${selection.logcatSnapshot != null}")
                        add("logcatCaptureScope=${LogcatSnapshotCollector.AppVisibleSnapshotScope}")
                        add("logcatByteCount=${selection.logcatSnapshot?.byteCount ?: 0}")
                        add("selectedSession=${selection.primarySession?.id ?: "latest-live"}")
                        runtimeId?.let { add("runtimeId=$it") }
                        mode?.let { add("mode=$it") }
                        policySignature?.let { add("policySignature=$it") }
                        fingerprintHash?.let {
                            add("fingerprintHash=$it")
                            add("networkScope=$it")
                        }
                        recentWarnings.firstOrNull()?.let { add("lastFailure=$it") }
                        allEvents.lifecycleMilestones().forEach { add("lifecycle=$it") }
                        selection.selectedApproachSummary?.let {
                            add("approach=${it.displayName}")
                            add("approachVerification=${it.verificationState}")
                            add("approachSuccessRate=${it.successRateLabel()}")
                            add("approachUsageCount=${it.usageCount}")
                            add("approachRuntimeMs=${it.totalRuntimeDurationMs}")
                        }
                    },
            )
        }

        private fun buildSummaryDocument(selection: DiagnosticsArchiveSelection) =
            projector.project(
                session = selection.primarySession,
                report = selection.primaryReport?.toSessionProjection(),
                latestSnapshotModel = selection.latestSnapshotModel?.let(redactor::redact),
                latestContextModel =
                    (selection.sessionContextModel ?: selection.latestContextModel)
                        ?.let(redactor::redact),
                latestTelemetry = selection.payload.telemetry.firstOrNull(),
                selectedResults = selection.primaryResults,
                warnings =
                    (selection.primaryEvents + selection.globalEvents).filter { event ->
                        event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
                    },
            )

        internal fun buildTelemetryCsv(payload: DiagnosticsArchivePayload): String =
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
                            if (sample.publicIp.isNullOrEmpty()) "" else "redacted",
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

        internal fun buildProbeResultsCsv(results: List<ProbeResultEntity>): String =
            buildString {
                appendLine("sessionId,probeType,target,outcome,probeRetryCount,createdAt,detailJson")
                results.forEach { result ->
                    appendLine(
                        listOf(
                            csvField(result.sessionId),
                            csvField(result.probeType),
                            csvField(result.target),
                            csvField(result.outcome),
                            csvField(result.probeRetryCount().orEmpty()),
                            csvField(result.createdAt),
                            csvField(result.detailJson),
                        ).joinToString(","),
                    )
                }
            }

        internal fun buildNativeEventsCsv(
            primaryEvents: List<NativeSessionEventEntity>,
            globalEvents: List<NativeSessionEventEntity>,
        ): String =
            buildString {
                appendLine(
                    "scope,sessionId,source,level,message,createdAt,runtimeId,mode,policySignature,fingerprintHash,subsystem",
                )
                primaryEvents.forEach { event ->
                    appendLine(
                        listOf(
                            csvField("session"),
                            csvField(event.sessionId.orEmpty()),
                            csvField(event.source),
                            csvField(event.level),
                            csvField(event.message),
                            csvField(event.createdAt),
                            csvField(event.runtimeId.orEmpty()),
                            csvField(event.mode.orEmpty()),
                            csvField(event.policySignature.orEmpty()),
                            csvField(event.fingerprintHash.orEmpty()),
                            csvField(event.subsystem.orEmpty()),
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
                            csvField(event.runtimeId.orEmpty()),
                            csvField(event.mode.orEmpty()),
                            csvField(event.policySignature.orEmpty()),
                            csvField(event.fingerprintHash.orEmpty()),
                            csvField(event.subsystem.orEmpty()),
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

        private fun ProbeResultEntity.probeRetryCount(): String? =
            runCatching {
                json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
            }.getOrNull()?.let(::deriveProbeRetryCount)?.toString()
    }

private fun List<NativeSessionEventEntity>.latestCorrelation(selector: (NativeSessionEventEntity) -> String?): String? =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .mapNotNull(selector)
        .firstOrNull()

private fun List<NativeSessionEventEntity>.lifecycleMilestones(limit: Int = 6): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            val subsystem = (event.subsystem ?: event.source).lowercase()
            val message = event.message.lowercase()
            subsystem in setOf("service", "proxy", "tunnel", "diagnostics") &&
                (message.contains("started") ||
                    message.contains("stopped") ||
                    message.contains("stop requested") ||
                    message.contains("listener started") ||
                    message.contains("listener stopped"))
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

private fun List<NativeSessionEventEntity>.recentWarningPreview(limit: Int = 5): List<String> =
    asSequence()
        .sortedByDescending(NativeSessionEventEntity::createdAt)
        .filter { event ->
            event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
        }.take(limit)
        .map { event -> "${event.subsystem ?: event.source}: ${event.message}" }
        .toList()

private fun BypassApproachSummary.successRateLabel(): String =
    validatedSuccessRate?.let { rate ->
        "${(rate * SuccessRatePercentScale).toInt()}%"
    } ?: "unverified"
