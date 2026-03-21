package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
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
        internal fun render(
            target: DiagnosticsArchiveTarget,
            selection: DiagnosticsArchiveSelection,
        ): List<DiagnosticsArchiveEntry> {
            val redactedPayload =
                selection.payload.copy(
                    sessionSnapshots = selection.payload.sessionSnapshots.map(redactor::redact),
                    sessionContexts = selection.payload.sessionContexts.map(redactor::redact),
                    latestPassiveSnapshot = selection.payload.latestPassiveSnapshot?.let(redactor::redact),
                    latestPassiveContext = selection.payload.latestPassiveContext?.let(redactor::redact),
                    telemetry =
                        selection.payload.telemetry.map { sample ->
                            sample.copy(publicIp = if (sample.publicIp != null) "redacted" else null)
                        },
                )
            val entries =
                mutableListOf(
                    DiagnosticsArchiveEntry(
                        name = "summary.txt",
                        bytes =
                            buildSummary(
                                createdAt = target.createdAt,
                                selection = selection,
                            ).toByteArray(),
                    ),
                    DiagnosticsArchiveEntry(
                        name = "report.json",
                        bytes =
                            json.encodeToString(
                                DiagnosticsArchivePayload.serializer(),
                                redactedPayload,
                            ).toByteArray(),
                    ),
                    DiagnosticsArchiveEntry(
                        name = "strategy-matrix.json",
                        bytes =
                            json.encodeToString(
                                StrategyMatrixArchivePayload.serializer(),
                                StrategyMatrixArchivePayload(
                                    sessionId = selection.primarySession?.id,
                                    profileId = selection.primarySession?.profileId,
                                    strategyProbeReport = selection.primaryReport?.strategyProbeReport,
                                ),
                            ).toByteArray(),
                    ),
                    DiagnosticsArchiveEntry(
                        name = "probe-results.csv",
                        bytes = buildProbeResultsCsv(selection.primaryResults).toByteArray(),
                    ),
                    DiagnosticsArchiveEntry(
                        name = "native-events.csv",
                        bytes =
                            buildNativeEventsCsv(
                                primaryEvents = selection.primaryEvents,
                                globalEvents = selection.globalEvents,
                            ).toByteArray(),
                    ),
                    DiagnosticsArchiveEntry(
                        name = "network-snapshots.json",
                        bytes =
                            json.encodeToString(
                                DiagnosticsArchiveSnapshotPayload.serializer(),
                                DiagnosticsArchiveSnapshotPayload(
                                    sessionSnapshots =
                                        selection.primarySnapshots.mapNotNull(redactor::decodeNetworkSnapshot)
                                            .map(redactor::redact),
                                    latestPassiveSnapshot =
                                        redactor.decodeNetworkSnapshot(selection.latestPassiveSnapshot)?.let(redactor::redact),
                                ),
                            ).toByteArray(),
                    ),
                    DiagnosticsArchiveEntry(
                        name = "diagnostic-context.json",
                        bytes =
                            json.encodeToString(
                                DiagnosticsArchiveContextPayload.serializer(),
                                DiagnosticsArchiveContextPayload(
                                    sessionContexts =
                                        selection.primaryContexts.mapNotNull(redactor::decodeDiagnosticContext)
                                            .map(redactor::redact),
                                    latestPassiveContext =
                                        redactor
                                            .decodeDiagnosticContext(selection.latestPassiveContext)
                                            ?.let(redactor::redact),
                                ),
                            ).toByteArray(),
                    ),
                )
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
                    latestTelemetrySummary = selection.payload.telemetry.firstOrNull()?.toArchiveTelemetrySummary(),
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
                appendLine("RIPDPI diagnostics archive")
                appendLine("generatedAt=$createdAt")
                appendLine("scope=${DiagnosticsArchiveFormat.scope}")
                appendLine("privacyMode=${DiagnosticsArchiveFormat.privacyMode}")
                appendLine("logcatIncluded=${selection.logcatSnapshot != null}")
                appendLine("logcatCaptureScope=${LogcatSnapshotCollector.AppVisibleSnapshotScope}")
                appendLine("logcatByteCount=${selection.logcatSnapshot?.byteCount ?: 0}")
                appendLine("selectedSession=${selection.primarySession?.id ?: "latest-live"}")
                selection.primarySession?.let {
                    appendLine("pathMode=${it.pathMode}")
                    appendLine("serviceMode=${it.serviceMode ?: "unknown"}")
                    appendLine("status=${it.status}")
                    appendLine("summary=${it.summary}")
                    selection.primaryReport?.strategyProbeReport?.let { strategyProbe ->
                        appendLine("strategySuite=${strategyProbe.suiteId}")
                        appendLine("strategyTcpCandidates=${strategyProbe.tcpCandidates.size}")
                        appendLine("strategyQuicCandidates=${strategyProbe.quicCandidates.size}")
                    }
                }
                selection.selectedApproachSummary?.let {
                    appendLine("approach=${it.displayName}")
                    appendLine("approachVerification=${it.verificationState}")
                    appendLine(
                        "approachSuccessRate=${it.validatedSuccessRate?.let { rate ->
                            "${(rate * 100).toInt()}%"
                        } ?: "unverified"}",
                    )
                    appendLine("approachUsageCount=${it.usageCount}")
                    appendLine("approachRuntimeMs=${it.totalRuntimeDurationMs}")
                }
                selection.latestSnapshotModel?.let(redactor::redact)?.toRedactedSummary()?.let { summary ->
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
                appendLine("resultCount=${selection.primaryResults.size}")
                selection.primaryResults.take(5).forEach { result ->
                    appendLine("${result.probeType}:${result.target}=${result.outcome}")
                }
                if (selection.globalEvents.isNotEmpty()) {
                    appendLine("recentWarnings=")
                    selection.globalEvents
                        .filter { event ->
                            event.level.equals("warn", ignoreCase = true) ||
                                event.level.equals("error", ignoreCase = true)
                        }.take(3)
                        .forEach { warning ->
                            appendLine("- ${warning.source}: ${warning.message}")
                        }
                }
            }.trim()

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
