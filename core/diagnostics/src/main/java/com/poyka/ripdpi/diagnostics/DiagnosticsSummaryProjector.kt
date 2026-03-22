package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryProjection
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
        ): DiagnosticsSummaryProjection =
            DiagnosticsSummaryProjection(
                sessionLines =
                    buildList {
                        session?.let {
                            add("pathMode=${it.pathMode}")
                            add("serviceMode=${it.serviceMode ?: "unknown"}")
                            add("status=${it.status}")
                            add("summary=${it.summary}")
                            add("startedAt=${it.startedAt}")
                            add("finishedAt=${it.finishedAt ?: "running"}")
                        }
                    },
                networkLines =
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
                    },
                contextLines =
                    buildList {
                        latestContextModel?.toRedactedSummary()?.let { summary ->
                            add("appVersion=${summary.device.appVersionName}")
                            add("device=${summary.device.deviceName}")
                            add("android=${summary.device.androidVersion}")
                            add("serviceMode=${summary.service.activeMode}")
                            add("profile=${summary.service.selectedProfileName}")
                            add("configSource=${summary.service.configSource}")
                            add("proxy=${summary.service.proxyEndpoint}")
                            add("vpnPermission=${summary.permissions.vpnPermissionState}")
                            add("notifications=${summary.permissions.notificationPermissionState}")
                            add("batteryOptimization=${summary.permissions.batteryOptimizationState}")
                            add("dataSaver=${summary.permissions.dataSaverState}")
                        }
                    },
                telemetryLines =
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
                    },
                resultLines =
                    buildList {
                        if (selectedResults.isNotEmpty()) {
                            add("results=${selectedResults.size}")
                            selectedResults.take(5).forEach { result ->
                                add("${result.probeType}:${result.target}=${result.outcome}")
                            }
                        }
                    },
                warningLines =
                    buildList {
                        if (warnings.isNotEmpty()) {
                            add("warnings=")
                            warnings.take(3).forEach { warning ->
                                add("- ${warning.source}: ${warning.message}")
                            }
                        }
                    },
                diagnoses = report?.diagnoses.orEmpty(),
                classifierVersion = report?.classifierVersion,
                packVersions = report?.packVersions.orEmpty(),
            )
    }
