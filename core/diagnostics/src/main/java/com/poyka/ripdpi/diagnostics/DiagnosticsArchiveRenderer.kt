package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveRenderer
    @Inject
    constructor(
        private val redactor: DiagnosticsArchiveRedactor,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        private companion object {
            private const val SummaryProbeResultPreviewCount = 5
            private const val SummaryWarningPreviewCount = 3
            private const val SuccessRatePercentScale = 100
        }

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
                    classifierVersion = selection.primaryReport?.classifierVersion,
                    diagnosisCount = selection.primaryReport?.diagnoses?.size ?: 0,
                    packVersions = selection.primaryReport?.packVersions.orEmpty(),
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
        ): String =
            buildString {
                appendSummaryHeader(createdAt, selection)
                appendSessionSummary(selection)
                appendApproachSummary(selection)
                appendNetworkSummary(selection)
                appendContextSummary(selection)
                appendTelemetrySummary(selection)
                appendResultSummary(selection)
                appendWarningSummary(selection)
            }.trim()

        private fun StringBuilder.appendSummaryHeader(
            createdAt: Long,
            selection: DiagnosticsArchiveSelection,
        ) {
            appendLine("RIPDPI diagnostics archive")
            appendLine("generatedAt=$createdAt")
            appendLine("scope=${DiagnosticsArchiveFormat.scope}")
            appendLine("privacyMode=${DiagnosticsArchiveFormat.privacyMode}")
            appendLine("logcatIncluded=${selection.logcatSnapshot != null}")
            appendLine("logcatCaptureScope=${LogcatSnapshotCollector.AppVisibleSnapshotScope}")
            appendLine("logcatByteCount=${selection.logcatSnapshot?.byteCount ?: 0}")
            appendLine("selectedSession=${selection.primarySession?.id ?: "latest-live"}")
        }

        private fun StringBuilder.appendSessionSummary(selection: DiagnosticsArchiveSelection) {
            val session = selection.primarySession ?: return
            appendLine("pathMode=${session.pathMode}")
            appendLine("serviceMode=${session.serviceMode ?: "unknown"}")
            appendLine("status=${session.status}")
            appendLine("summary=${session.summary}")
            selection.primaryReport?.strategyProbeReport?.let { strategyProbe ->
                appendLine("strategySuite=${strategyProbe.suiteId}")
                appendLine("strategyTcpCandidates=${strategyProbe.tcpCandidates.size}")
                appendLine("strategyQuicCandidates=${strategyProbe.quicCandidates.size}")
            }
            selection.primaryReport?.classifierVersion?.let { appendLine("classifierVersion=$it") }
            selection.primaryReport?.diagnoses?.takeIf(List<*>::isNotEmpty)?.let { diagnoses ->
                appendLine("diagnosisCount=${diagnoses.size}")
                diagnoses.forEach { diagnosis ->
                    appendLine("diagnosis.${diagnosis.code}=${diagnosis.summary}")
                }
            }
            selection.primaryReport?.packVersions?.takeIf(Map<*, *>::isNotEmpty)?.forEach { (packId, version) ->
                appendLine("pack.$packId=$version")
            }
        }

        private fun StringBuilder.appendApproachSummary(selection: DiagnosticsArchiveSelection) {
            selection.selectedApproachSummary?.let {
                appendLine("approach=${it.displayName}")
                appendLine("approachVerification=${it.verificationState}")
                appendLine("approachSuccessRate=${it.successRateLabel()}")
                appendLine("approachUsageCount=${it.usageCount}")
                appendLine("approachRuntimeMs=${it.totalRuntimeDurationMs}")
            }
        }

        private fun StringBuilder.appendNetworkSummary(selection: DiagnosticsArchiveSelection) {
            selection.latestSnapshotModel?.let(redactor::redact)?.toRedactedSummary()?.let { summary ->
                appendLine("transport=${summary.transport}")
                appendLine("dns=${summary.dnsServers}")
                appendLine("privateDns=${summary.privateDnsMode}")
                appendLine("publicIp=${summary.publicIp}")
                appendLine("publicAsn=${summary.publicAsn}")
                appendLine("localAddresses=${summary.localAddresses}")
                appendLine("validated=${summary.networkValidated}")
                appendLine("captivePortal=${summary.captivePortalDetected}")
                summary.wifiDetails?.let { appendWifiSummary(it) }
                summary.cellularDetails?.let { appendCellularSummary(it) }
            }
        }

        private fun StringBuilder.appendWifiSummary(wifi: RedactedWifiSummary) {
            appendLine("wifiSsid=${wifi.ssid}")
            appendLine("wifiBand=${wifi.band}")
            appendLine("wifiStandard=${wifi.wifiStandard}")
            appendLine("wifiFrequencyMhz=${wifi.frequencyMhz ?: "unknown"}")
            appendLine("wifiLinkSpeedMbps=${wifi.linkSpeedMbps ?: "unknown"}")
            appendLine("wifiSignalDbm=${wifi.rssiDbm ?: "unknown"}")
            appendLine("wifiGateway=${wifi.gateway}")
        }

        private fun StringBuilder.appendCellularSummary(cellular: RedactedCellularSummary) {
            appendLine("carrier=${cellular.carrierName}")
            appendLine("networkOperator=${cellular.networkOperatorName}")
            appendLine("dataNetwork=${cellular.dataNetworkType}")
            appendLine("voiceNetwork=${cellular.voiceNetworkType}")
            appendLine("networkCountry=${cellular.networkCountryIso}")
            appendLine("roaming=${cellular.isNetworkRoaming ?: "unknown"}")
            appendLine("signalLevel=${cellular.signalLevel ?: "unknown"}")
            appendLine("signalDbm=${cellular.signalDbm ?: "unknown"}")
        }

        private fun StringBuilder.appendContextSummary(selection: DiagnosticsArchiveSelection) {
            (selection.sessionContextModel ?: selection.latestContextModel)
                ?.let(redactor::redact)
                ?.toRedactedSummary()
                ?.let { contextSummary ->
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
        }

        private fun StringBuilder.appendTelemetrySummary(selection: DiagnosticsArchiveSelection) {
            selection.payload.telemetry.firstOrNull()?.let { sample ->
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
        }

        private fun StringBuilder.appendResultSummary(selection: DiagnosticsArchiveSelection) {
            appendLine("resultCount=${selection.primaryResults.size}")
            selection.primaryResults.take(SummaryProbeResultPreviewCount).forEach { result ->
                appendLine("${result.probeType}:${result.target}=${result.outcome}")
            }
        }

        private fun StringBuilder.appendWarningSummary(selection: DiagnosticsArchiveSelection) {
            val warnings =
                selection.globalEvents.filter { event ->
                    event.level.equals("warn", ignoreCase = true) ||
                        event.level.equals("error", ignoreCase = true)
                }
            if (warnings.isEmpty()) return
            appendLine("recentWarnings=")
            warnings.take(SummaryWarningPreviewCount).forEach { warning ->
                appendLine("- ${warning.source}: ${warning.message}")
            }
        }

        private fun BypassApproachSummary.successRateLabel(): String =
            validatedSuccessRate?.let { rate ->
                "${(rate * SuccessRatePercentScale).toInt()}%"
            } ?: "unverified"

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

        private fun ProbeResultEntity.probeRetryCount(): String? =
            runCatching {
                json.decodeFromString(ListSerializer(ProbeDetail.serializer()), detailJson)
            }.getOrNull()?.let(::deriveProbeRetryCount)?.toString()
    }
