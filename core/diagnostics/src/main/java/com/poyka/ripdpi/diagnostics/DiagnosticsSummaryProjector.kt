package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsHighlight
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryDocument
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummarySection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsSummaryProjector
    @Inject
    constructor() {
        fun project(
            session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity?,
            report: DiagnosticsSessionProjection?,
            latestSnapshotModel: NetworkSnapshotModel?,
            latestContextModel: DiagnosticContextModel?,
            latestTelemetry: TelemetrySampleEntity?,
            selectedResults: List<ProbeResultEntity>,
            warnings: List<NativeSessionEventEntity>,
        ): DiagnosticsSummaryDocument {
            val headerLines =
                buildList {
                    session?.let {
                        add("sessionId=${it.id}")
                        add("pathMode=${it.pathMode}")
                        add("serviceMode=${it.serviceMode ?: "unknown"}")
                        add("status=${it.status}")
                        add("summary=${it.summary}")
                        add("startedAt=${it.startedAt}")
                        add("finishedAt=${it.finishedAt ?: "running"}")
                    }
                }
            val metadataLines =
                buildList {
                    report?.strategyProbeReport?.let { strategyProbe ->
                        add("strategySuite=${strategyProbe.suiteId}")
                        add("strategyTcpCandidates=${strategyProbe.tcpCandidates.size}")
                        add("strategyQuicCandidates=${strategyProbe.quicCandidates.size}")
                    }
                    report?.engineAnalysisVersion?.let { add("engineAnalysisVersion=$it") }
                    report?.classifierVersion?.let { add("classifierVersion=$it") }
                    if (report?.packVersions?.isNotEmpty() == true) {
                        report.packVersions.forEach { (packId, version) ->
                            add("pack.$packId=$version")
                        }
                    }
                    if (report?.diagnoses?.isNotEmpty() == true) {
                        add("diagnosisCount=${report.diagnoses.size}")
                    }
                }
            val environmentLines =
                buildList {
                    latestSnapshotModel?.toRedactedSummary()?.let { snapshot ->
                        add("transport=${snapshot.transport}")
                        add("publicIp=${snapshot.publicIp}")
                        add("publicAsn=${snapshot.publicAsn}")
                        add("dns=${snapshot.dnsServers}")
                        add("privateDns=${snapshot.privateDnsMode}")
                        add("validated=${snapshot.networkValidated}")
                        snapshot.wifiDetails?.let { wifi ->
                            add("wifiSsid=${wifi.ssid}")
                            add("wifiBand=${wifi.band}")
                            add("wifiStandard=${wifi.wifiStandard}")
                            add("wifiSignal=${wifi.rssiDbm ?: "unknown"}")
                        }
                        snapshot.cellularDetails?.let { cellular ->
                            add("carrier=${cellular.carrierName}")
                            add("networkOperator=${cellular.networkOperatorName}")
                            add("dataNetwork=${cellular.dataNetworkType}")
                            add("voiceNetwork=${cellular.voiceNetworkType}")
                            add("roaming=${cellular.isNetworkRoaming ?: "unknown"}")
                        }
                    }
                    latestContextModel?.toRedactedSummary()?.let { summary ->
                        add("appVersion=${summary.device.appVersionName}")
                        add("device=${summary.device.deviceName}")
                        add("android=${summary.device.androidVersion}")
                        add("activeMode=${summary.service.activeMode}")
                        add("profile=${summary.service.selectedProfileName}")
                        add("configSource=${summary.service.configSource}")
                        add("proxy=${summary.service.proxyEndpoint}")
                        add("vpnPermission=${summary.permissions.vpnPermissionState}")
                        add("notifications=${summary.permissions.notificationPermissionState}")
                        add("batteryOptimization=${summary.permissions.batteryOptimizationState}")
                        add("dataSaver=${summary.permissions.dataSaverState}")
                    }
                }
            val telemetryLines =
                buildList {
                    latestTelemetry?.let {
                        add("networkType=${it.networkType}")
                        add("failureClass=${it.failureClass ?: "none"}")
                        add("winningStrategyFamily=${it.winningStrategyFamily() ?: "none"}")
                        add("telemetryNetworkFingerprintHash=${it.telemetryNetworkFingerprintHash ?: "none"}")
                        add("rttBand=${it.rttBand()}")
                        add("retryCount=${it.retryCount()}")
                        add("resolverId=${it.resolverId ?: "unknown"}")
                        add("resolverProtocol=${it.resolverProtocol ?: "unknown"}")
                        add("resolverEndpoint=${it.resolverEndpoint ?: "unknown"}")
                        add("resolverLatencyMs=${it.resolverLatencyMs ?: 0}")
                        add("dnsFailuresTotal=${it.dnsFailuresTotal}")
                        add("resolverFallback=${it.resolverFallbackReason ?: it.resolverFallbackActive}")
                        add("networkHandoverClass=${it.networkHandoverClass ?: "none"}")
                        add("txBytes=${it.txBytes}")
                        add("rxBytes=${it.rxBytes}")
                        add("txPackets=${it.txPackets}")
                        add("rxPackets=${it.rxPackets}")
                    }
                }
            val rawPreviewLines =
                buildList {
                    if (selectedResults.isNotEmpty()) {
                        add("results=${selectedResults.size}")
                        selectedResults.take(5).forEach { result ->
                            add("${result.probeType}:${result.target}=${result.outcome}")
                        }
                    }
                }
            val warningLines =
                buildList {
                    warnings.take(3).forEach { warning ->
                        add("${warning.source}: ${warning.message}")
                    }
                }
            val highlights =
                buildList {
                    report?.strategyProbeReport?.let { strategyProbe ->
                        add(
                            DiagnosticsHighlight(
                                title = "strategy",
                                summary =
                                    "${strategyProbe.recommendation.tcpCandidateLabel} + " +
                                        strategyProbe.recommendation.quicCandidateLabel,
                            ),
                        )
                    }
                    report?.diagnoses?.take(3)?.forEach { diagnosis ->
                        add(DiagnosticsHighlight(title = diagnosis.code, summary = diagnosis.summary))
                    }
                }
            return DiagnosticsSummaryDocument(
                header = DiagnosticsSummarySection(title = "Header", lines = headerLines),
                reportMetadata = DiagnosticsSummarySection(title = "Report", lines = metadataLines),
                environment = DiagnosticsSummarySection(title = "Environment", lines = environmentLines),
                telemetry = DiagnosticsSummarySection(title = "Telemetry", lines = telemetryLines),
                rawPreview = DiagnosticsSummarySection(title = "Raw Preview", lines = rawPreviewLines),
                warnings = DiagnosticsSummarySection(title = "Warnings", lines = warningLines),
                diagnoses = report?.diagnoses.orEmpty(),
                highlights = highlights,
                observations = report?.observations.orEmpty(),
                engineAnalysisVersion = report?.engineAnalysisVersion,
                classifierVersion = report?.classifierVersion,
                packVersions = report?.packVersions.orEmpty(),
            )
        }
    }
