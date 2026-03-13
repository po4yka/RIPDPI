package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

@Suppress("LongMethod")
internal object DiagnosticsShareSummaryBuilder {

    suspend fun build(
        sessionId: String?,
        historyRepository: DiagnosticsHistoryRepository,
        json: Json,
    ): ShareSummary {
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
        val body = buildBody(
            selectedSession = selectedSession,
            selectedResults = selectedResults,
            latestSnapshotModel = latestSnapshotModel,
            latestContextModel = latestContextModel,
            latestTelemetry = latestTelemetry,
            latestWarnings = latestWarnings,
        )
        return ShareSummary(
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

    @Suppress("CyclomaticComplexMethod")
    private fun buildBody(
        selectedSession: ScanSessionEntity?,
        selectedResults: List<ProbeResultEntity>,
        latestSnapshotModel: NetworkSnapshotModel?,
        latestContextModel: DiagnosticContextModel?,
        latestTelemetry: TelemetrySampleEntity?,
        latestWarnings: List<NativeSessionEventEntity>,
    ): String =
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
}
