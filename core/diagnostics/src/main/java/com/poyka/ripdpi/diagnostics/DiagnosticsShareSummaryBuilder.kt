package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private const val ShareSummaryResultPreviewLimit = 5
private const val ShareSummaryWarningPreviewLimit = 3

internal object DiagnosticsShareSummaryBuilder {
    suspend fun build(
        sessionId: String?,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactReadStore: DiagnosticsArtifactReadStore,
        json: Json,
    ): ShareSummary {
        val selectedSession =
            sessionId
                ?.let { id -> scanRecordStore.getScanSession(id) }
                ?: scanRecordStore.observeRecentScanSessions(limit = 1).first().firstOrNull()
        val selectedResults =
            selectedSession?.id?.let { id -> scanRecordStore.getProbeResults(id) }.orEmpty()
        val latestSnapshot =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.observeSnapshots(limit = 200).first().firstOrNull { it.sessionId == id }
                }
                ?: artifactReadStore.observeSnapshots(limit = 1).first().firstOrNull()
        val latestSnapshotModel =
            latestSnapshot
                ?.payloadJson
                ?.let { payload ->
                    runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payload) }.getOrNull()
                }
        val latestContext =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.observeContexts(limit = 200).first().firstOrNull { it.sessionId == id }
                }
                ?: artifactReadStore.observeContexts(limit = 1).first().firstOrNull()
        val latestContextModel =
            latestContext
                ?.payloadJson
                ?.let { payload ->
                    runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payload) }.getOrNull()
                }
        val latestTelemetry = artifactReadStore.observeTelemetry(limit = 1).first().firstOrNull()
        val latestWarnings =
            artifactReadStore.observeNativeEvents(limit = 50).first().filter {
                it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
            }
        val selectedReport =
            selectedSession
                ?.reportJson
                ?.let { reportJson ->
                    runCatching { json.decodeFromString(ScanReport.serializer(), reportJson) }.getOrNull()
                }
        val title =
            selectedSession?.let { "RIPDPI diagnostics ${it.id.take(8)}" } ?: "RIPDPI diagnostics summary"
        val body =
            buildBody(
                selectedSession = selectedSession,
                selectedReport = selectedReport,
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

    private fun buildBody(
        selectedSession: ScanSessionEntity?,
        selectedReport: ScanReport?,
        selectedResults: List<ProbeResultEntity>,
        latestSnapshotModel: NetworkSnapshotModel?,
        latestContextModel: DiagnosticContextModel?,
        latestTelemetry: TelemetrySampleEntity?,
        latestWarnings: List<NativeSessionEventEntity>,
    ): String =
        buildString {
            appendLine("RIPDPI diagnostics summary")
            appendLine("session=${selectedSession?.id ?: "latest-live"}")
            appendSessionSection(selectedSession)
            appendSnapshotSection(latestSnapshotModel)
            appendContextSection(latestContextModel)
            appendTelemetrySection(latestTelemetry)
            appendResultsSection(selectedResults)
            appendReportSection(selectedReport)
            appendWarningsSection(latestWarnings)
        }
}

private fun StringBuilder.appendSessionSection(selectedSession: ScanSessionEntity?) {
    selectedSession ?: return
    appendLine("pathMode=${selectedSession.pathMode}")
    appendLine("serviceMode=${selectedSession.serviceMode ?: "unknown"}")
    appendLine("status=${selectedSession.status}")
    appendLine("summary=${selectedSession.summary}")
    appendLine("startedAt=${selectedSession.startedAt}")
    appendLine("finishedAt=${selectedSession.finishedAt ?: "running"}")
}

private fun StringBuilder.appendSnapshotSection(latestSnapshotModel: NetworkSnapshotModel?) {
    val snapshot = latestSnapshotModel?.toRedactedSummary() ?: return
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

private fun StringBuilder.appendContextSection(latestContextModel: DiagnosticContextModel?) {
    val contextSummary = latestContextModel?.toRedactedSummary() ?: return
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

private fun StringBuilder.appendTelemetrySection(latestTelemetry: TelemetrySampleEntity?) {
    latestTelemetry ?: return
    appendLine("networkType=${latestTelemetry.networkType}")
    appendLine("failureClass=${latestTelemetry.failureClass ?: "none"}")
    appendLine("winningStrategyFamily=${latestTelemetry.winningStrategyFamily() ?: "none"}")
    appendLine("telemetryNetworkFingerprintHash=${latestTelemetry.telemetryNetworkFingerprintHash ?: "none"}")
    appendLine("rttBand=${latestTelemetry.rttBand()}")
    appendLine("retryCount=${latestTelemetry.retryCount()}")
    appendLine("resolverId=${latestTelemetry.resolverId ?: "unknown"}")
    appendLine("resolverProtocol=${latestTelemetry.resolverProtocol ?: "unknown"}")
    appendLine("resolverEndpoint=${latestTelemetry.resolverEndpoint ?: "unknown"}")
    appendLine("resolverLatencyMs=${latestTelemetry.resolverLatencyMs ?: 0}")
    appendLine("dnsFailuresTotal=${latestTelemetry.dnsFailuresTotal}")
    appendLine("resolverFallback=${latestTelemetry.resolverFallbackReason ?: latestTelemetry.resolverFallbackActive}")
    appendLine("networkHandoverClass=${latestTelemetry.networkHandoverClass ?: "none"}")
    appendLine("txBytes=${latestTelemetry.txBytes}")
    appendLine("rxBytes=${latestTelemetry.rxBytes}")
    appendLine("txPackets=${latestTelemetry.txPackets}")
    appendLine("rxPackets=${latestTelemetry.rxPackets}")
}

private fun StringBuilder.appendResultsSection(selectedResults: List<ProbeResultEntity>) {
    if (selectedResults.isEmpty()) return
    appendLine("results=${selectedResults.size}")
    selectedResults.take(ShareSummaryResultPreviewLimit).forEach { result ->
        appendLine("${result.probeType}:${result.target}=${result.outcome}")
    }
}

private fun StringBuilder.appendReportSection(selectedReport: ScanReport?) {
    selectedReport ?: return
    selectedReport.classifierVersion?.let { appendLine("classifierVersion=$it") }
    if (selectedReport.diagnoses.isNotEmpty()) {
        appendLine("diagnosisCount=${selectedReport.diagnoses.size}")
        selectedReport.diagnoses.take(ShareSummaryResultPreviewLimit).forEach { diagnosis ->
            appendLine("diagnosis.${diagnosis.code}=${diagnosis.summary}")
        }
    }
    selectedReport.packVersions.forEach { (packId, version) ->
        appendLine("pack.$packId=$version")
    }
}

private fun StringBuilder.appendWarningsSection(latestWarnings: List<NativeSessionEventEntity>) {
    if (latestWarnings.isEmpty()) return
    appendLine("warnings=")
    latestWarnings.take(ShareSummaryWarningPreviewLimit).forEach { warning ->
        appendLine("- ${warning.source}: ${warning.message}")
    }
}
