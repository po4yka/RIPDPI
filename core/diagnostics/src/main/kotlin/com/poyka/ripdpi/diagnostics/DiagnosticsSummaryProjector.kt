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
        private companion object {
            const val ResultPreviewLimit = 5
            const val WarningPreviewLimit = 3
            const val DiagnosisHighlightLimit = 3
        }

        fun project(
            session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity?,
            report: DiagnosticsSessionProjection?,
            latestSnapshotModel: NetworkSnapshotModel?,
            latestContextModel: DiagnosticContextModel?,
            latestTelemetry: TelemetrySampleEntity?,
            selectedResults: List<ProbeResultEntity>,
            warnings: List<NativeSessionEventEntity>,
        ): DiagnosticsSummaryDocument =
            DiagnosticsSummaryDocument(
                header = DiagnosticsSummarySection(title = "Header", lines = buildHeaderLines(session, report)),
                reportMetadata = DiagnosticsSummarySection(title = "Report", lines = buildMetadataLines(report)),
                environment =
                    DiagnosticsSummarySection(
                        title = "Environment",
                        lines = buildEnvironmentLines(latestSnapshotModel, latestContextModel),
                    ),
                telemetry =
                    DiagnosticsSummarySection(
                        title = "Telemetry",
                        lines = buildTelemetryLines(latestTelemetry),
                    ),
                rawPreview =
                    DiagnosticsSummarySection(
                        title = "Raw Preview",
                        lines = buildRawPreviewLines(selectedResults),
                    ),
                warnings = DiagnosticsSummarySection(title = "Warnings", lines = buildWarningLines(warnings)),
                diagnoses = report?.diagnoses.orEmpty(),
                highlights = buildHighlights(report),
                observations = report?.observations.orEmpty(),
                engineAnalysisVersion = report?.engineAnalysisVersion,
                classifierVersion = report?.classifierVersion,
                packVersions = report?.packVersions.orEmpty(),
            )

        private fun buildHeaderLines(
            session: com.poyka.ripdpi.data.diagnostics.ScanSessionEntity?,
            report: DiagnosticsSessionProjection?,
        ): List<String> =
            buildList {
                session?.let {
                    add("sessionId=${it.id}")
                    add("pathMode=${it.pathMode}")
                    add("serviceMode=${it.serviceMode ?: "unknown"}")
                    add("status=${it.status}")
                    add("summary=${it.displaySummary(report)}")
                    add("startedAt=${it.startedAt}")
                    add("finishedAt=${it.finishedAt ?: "running"}")
                }
            }

        private fun buildMetadataLines(report: DiagnosticsSessionProjection?): List<String> =
            buildList {
                report?.strategyProbeReport?.let { strategyProbe ->
                    val recommendedTcp =
                        strategyProbe.tcpCandidates.firstOrNull { candidate ->
                            candidate.id == strategyProbe.recommendation.tcpCandidateId
                        }
                    val recommendedQuic =
                        strategyProbe.quicCandidates.firstOrNull { candidate ->
                            candidate.id == strategyProbe.recommendation.quicCandidateId
                        }
                    add("strategySuite=${strategyProbe.suiteId}")
                    add("strategyMethodology=${strategyProbe.methodologyVersion}")
                    add("strategyCompletionKind=${strategyProbe.completionKind.name}")
                    add("strategyTcpCandidates=${strategyProbe.tcpCandidates.size}")
                    add("strategyQuicCandidates=${strategyProbe.quicCandidates.size}")
                    if (strategyProbe.pilotBucketLabels.isNotEmpty()) {
                        add("strategyPilotBuckets=${strategyProbe.pilotBucketLabels.joinToString("|")}")
                    }
                    strategyProbe.targetSelection?.let { selection ->
                        add("strategyTargetCohort=${selection.cohortId}")
                        add("strategyTargetCohortLabel=${selection.cohortLabel}")
                        add("strategyTargetDomains=${selection.domainHosts.joinToString("|")}")
                        add("strategyTargetQuicHosts=${selection.quicHosts.joinToString("|")}")
                    }
                    strategyProbe.auditAssessment?.let { assessment ->
                        add("strategyConfidence=${assessment.confidence.level.name}")
                        add("strategyConfidenceScore=${assessment.confidence.score}")
                        add("strategyMatrixCoverage=${assessment.coverage.matrixCoveragePercent}")
                        add("strategyWinnerCoverage=${assessment.coverage.winnerCoveragePercent}")
                    }
                    recommendedTcp?.let { tcp ->
                        add("strategyRecommendedTcpEmitterTier=${tcp.emitterTier.name}")
                        if (tcp.exactEmitterRequiresRoot) {
                            add("strategyRecommendedTcpRequiresRoot=true")
                        }
                        if (tcp.emitterDowngraded) {
                            add("strategyRecommendedTcpDowngraded=true")
                        }
                    }
                    recommendedQuic?.let { quic ->
                        add("strategyRecommendedQuicEmitterTier=${quic.emitterTier.name}")
                        if (quic.exactEmitterRequiresRoot) {
                            add("strategyRecommendedQuicRequiresRoot=true")
                        }
                        if (quic.emitterDowngraded) {
                            add("strategyRecommendedQuicDowngraded=true")
                        }
                    }
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

        private fun buildEnvironmentLines(
            latestSnapshotModel: NetworkSnapshotModel?,
            latestContextModel: DiagnosticContextModel?,
        ): List<String> =
            buildList {
                appendSnapshotLines(latestSnapshotModel)
                appendContextLines(latestContextModel)
            }

        private fun MutableList<String>.appendSnapshotLines(latestSnapshotModel: NetworkSnapshotModel?) {
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
        }

        private fun MutableList<String>.appendContextLines(latestContextModel: DiagnosticContextModel?) {
            latestContextModel?.toRedactedSummary()?.let { summary ->
                add("appVersion=${summary.device.appVersionName}")
                add("device=${summary.device.deviceName}")
                add("android=${summary.device.androidVersion}")
                add("activeMode=${summary.service.activeMode}")
                add("profile=${summary.service.selectedProfileName}")
                add("configSource=${summary.service.configSource}")
                add("proxy=${summary.service.proxyEndpoint}")
                summary.service.proxyRuntime?.let {
                    add(
                        "proxyRuntime=${it.state}/${it.health}:${it.lastFailureClass}",
                    )
                }
                summary.service.tunnelRuntime?.let {
                    add(
                        "tunnelRuntime=${it.state}/${it.health}:${it.lastFailureClass}",
                    )
                }
                add("vpnPermission=${summary.permissions.vpnPermissionState}")
                add("notifications=${summary.permissions.notificationPermissionState}")
                add("batteryOptimization=${summary.permissions.batteryOptimizationState}")
                add("dataSaver=${summary.permissions.dataSaverState}")
            }
        }

        private fun buildTelemetryLines(latestTelemetry: TelemetrySampleEntity?): List<String> =
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

        private fun buildRawPreviewLines(selectedResults: List<ProbeResultEntity>): List<String> =
            buildList {
                if (selectedResults.isNotEmpty()) {
                    add("results=${selectedResults.size}")
                    selectedResults.take(ResultPreviewLimit).forEach { result ->
                        add("${result.probeType}:${result.target}=${result.outcome}")
                    }
                }
            }

        private fun buildWarningLines(warnings: List<NativeSessionEventEntity>): List<String> =
            warnings.take(WarningPreviewLimit).map { warning ->
                "${warning.source}: ${warning.message}"
            }

        private fun buildHighlights(report: DiagnosticsSessionProjection?): List<DiagnosticsHighlight> =
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
                report?.diagnoses?.take(DiagnosisHighlightLimit)?.forEach { diagnosis ->
                    add(DiagnosticsHighlight(title = diagnosis.code, summary = diagnosis.summary))
                }
            }
    }
