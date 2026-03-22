package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.rttBand
import com.poyka.ripdpi.diagnostics.winningStrategyFamily
import java.util.Locale
import javax.inject.Inject

internal class HistoryConnectionDetailUiFactory
    @Inject
    constructor(
        private val coreSupport: DiagnosticsUiCoreSupport,
    ) {
        fun toConnectionRowUiModel(session: DiagnosticConnectionSession): HistoryConnectionRowUiModel {
            val durationMs =
                (session.finishedAt ?: session.updatedAt).coerceAtLeast(session.startedAt) - session.startedAt
            val summary =
                session.failureMessage
                    ?: session.endedReason
                    ?: "${session.serviceMode} on ${session.networkType}"
            return HistoryConnectionRowUiModel(
                id = session.id,
                title = "${session.serviceMode} ${session.connectionState.lowercase(Locale.US)}",
                subtitle = "${session.networkType} · ${coreSupport.formatTimestamp(session.startedAt)}",
                serviceMode = session.serviceMode,
                connectionState = session.connectionState,
                networkType = session.networkType,
                startedAtLabel = coreSupport.formatTimestamp(session.startedAt),
                summary = summary,
                metrics =
                    listOf(
                        DiagnosticsMetricUiModel("Duration", formatDurationMs(durationMs)),
                        DiagnosticsMetricUiModel("TX", coreSupport.formatBytes(session.txBytes), DiagnosticsTone.Info),
                        DiagnosticsMetricUiModel("RX", coreSupport.formatBytes(session.rxBytes), DiagnosticsTone.Positive),
                        DiagnosticsMetricUiModel(
                            "Errors",
                            session.totalErrors.toString(),
                            if (session.totalErrors > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                        ),
                    ),
                tone = toneForConnection(session),
            )
        }

        fun toConnectionDetail(detail: DiagnosticConnectionDetail): HistoryConnectionDetailUiModel {
            val row = toConnectionRowUiModel(detail.session)
            val latestTelemetry = detail.telemetry.maxByOrNull { it.createdAt }
            return HistoryConnectionDetailUiModel(
                session = row,
                highlights =
                    buildList {
                        add(DiagnosticsMetricUiModel("Network", detail.session.networkType, DiagnosticsTone.Info))
                        add(
                            DiagnosticsMetricUiModel(
                                "Health",
                                detail.session.health.replaceFirstChar { it.uppercase() },
                                toneForConnection(detail.session),
                            ),
                        )
                        add(DiagnosticsMetricUiModel("TX", coreSupport.formatBytes(detail.session.txBytes), DiagnosticsTone.Info))
                        add(
                            DiagnosticsMetricUiModel(
                                "RX",
                                coreSupport.formatBytes(detail.session.rxBytes),
                                DiagnosticsTone.Positive,
                            ),
                        )
                        add(
                            DiagnosticsMetricUiModel(
                                "Errors",
                                detail.session.totalErrors.toString(),
                                if (detail.session.totalErrors > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                            ),
                        )
                        add(
                            DiagnosticsMetricUiModel(
                                "Route changes",
                                detail.session.routeChanges.toString(),
                                DiagnosticsTone.Info,
                            ),
                        )
                        (latestTelemetry?.failureClass ?: detail.session.failureClass)?.let { failure ->
                            add(DiagnosticsMetricUiModel("Failure class", failure, DiagnosticsTone.Warning))
                        }
                        (latestTelemetry?.winningStrategyFamily() ?: detail.session.winningStrategyFamily())?.let { winningStrategy ->
                            add(DiagnosticsMetricUiModel("Strategy", winningStrategy, DiagnosticsTone.Positive))
                        }
                        add(
                            DiagnosticsMetricUiModel(
                                "RTT band",
                                latestTelemetry?.rttBand() ?: detail.session.rttBand(),
                                DiagnosticsTone.Info,
                            ),
                        )
                        val retryCount = latestTelemetry?.retryCount() ?: detail.session.retryCount()
                        add(
                            DiagnosticsMetricUiModel(
                                "Retries",
                                retryCount.toString(),
                                if (retryCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                            ),
                        )
                    },
                contextGroups =
                    buildList {
                        addAll(
                            detail.contexts
                                .mapNotNull { it.context }
                                .flatMap { context -> context.toContextGroups() }
                                .distinctBy { group ->
                                    group.title +
                                        group.fields.joinToString { field ->
                                            "${field.label}:${field.value}"
                                        }
                                },
                        )
                        buildFieldTelemetryGroup(detail.session, latestTelemetry)?.let(::add)
                    },
                snapshots = detail.snapshots.mapNotNull(::toSnapshotUiModel),
                events = detail.events.map(coreSupport::toEventUiModel),
            )
        }

        private fun toSnapshotUiModel(snapshotEntity: DiagnosticNetworkSnapshot): DiagnosticsNetworkSnapshotUiModel? {
            val snapshot = snapshotEntity.snapshot ?: return null
            return DiagnosticsNetworkSnapshotUiModel(
                title = snapshotEntity.snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
                subtitle = "${snapshot.transport} · ${coreSupport.formatTimestamp(snapshot.capturedAt)}",
                fields =
                    listOf(
                        DiagnosticsFieldUiModel("DNS", snapshot.dnsServers.joinToString().ifBlank { "Unknown" }),
                        DiagnosticsFieldUiModel("Private DNS", snapshot.privateDnsMode),
                        DiagnosticsFieldUiModel("Public IP", snapshot.publicIp ?: "Unknown"),
                        DiagnosticsFieldUiModel("Validated", snapshot.networkValidated.toString()),
                        DiagnosticsFieldUiModel("Captive portal", snapshot.captivePortalDetected.toString()),
                    ),
            )
        }

        private fun DiagnosticContextModel.toContextGroups(): List<DiagnosticsContextGroupUiModel> =
            listOf(
                DiagnosticsContextGroupUiModel(
                    title = "Service",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("Status", service.serviceStatus),
                            DiagnosticsFieldUiModel("Mode", service.activeMode),
                            DiagnosticsFieldUiModel("Profile", service.selectedProfileName),
                            DiagnosticsFieldUiModel("Config source", service.configSource),
                            DiagnosticsFieldUiModel("Proxy", service.proxyEndpoint),
                            DiagnosticsFieldUiModel("Chain", service.chainSummary),
                            DiagnosticsFieldUiModel("Last native error", service.lastNativeErrorHeadline),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Environment",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("VPN permission", permissions.vpnPermissionState),
                            DiagnosticsFieldUiModel("Notifications", permissions.notificationPermissionState),
                            DiagnosticsFieldUiModel("Battery optimization", permissions.batteryOptimizationState),
                            DiagnosticsFieldUiModel("Data saver", permissions.dataSaverState),
                            DiagnosticsFieldUiModel("Power save", environment.powerSaveModeState),
                            DiagnosticsFieldUiModel("Metered", environment.networkMeteredState),
                            DiagnosticsFieldUiModel("Roaming", environment.roamingState),
                        ),
                ),
                DiagnosticsContextGroupUiModel(
                    title = "Device",
                    fields =
                        listOf(
                            DiagnosticsFieldUiModel("App", device.appVersionName),
                            DiagnosticsFieldUiModel("Device", "${device.manufacturer} ${device.model}"),
                            DiagnosticsFieldUiModel("Android", "${device.androidVersion} (API ${device.apiLevel})"),
                            DiagnosticsFieldUiModel("Locale", device.locale),
                        ),
                ),
            )

        private fun buildFieldTelemetryGroup(
            session: DiagnosticConnectionSession,
            telemetry: DiagnosticTelemetrySample?,
        ): DiagnosticsContextGroupUiModel? {
            val failureClass = telemetry?.failureClass ?: session.failureClass
            val winningTcpStrategyFamily = telemetry?.winningTcpStrategyFamily ?: session.winningTcpStrategyFamily
            val winningQuicStrategyFamily = telemetry?.winningQuicStrategyFamily ?: session.winningQuicStrategyFamily
            val winningStrategyFamily = telemetry?.winningStrategyFamily() ?: session.winningStrategyFamily()
            val telemetryNetworkFingerprintHash =
                telemetry?.telemetryNetworkFingerprintHash ?: session.telemetryNetworkFingerprintHash
            val proxyRttBand = telemetry?.proxyRttBand ?: session.proxyRttBand
            val resolverRttBand = telemetry?.resolverRttBand ?: session.resolverRttBand
            val rttBand = telemetry?.rttBand() ?: session.rttBand()
            val proxyRouteRetryCount = telemetry?.proxyRouteRetryCount ?: session.proxyRouteRetryCount
            val tunnelRecoveryRetryCount = telemetry?.tunnelRecoveryRetryCount ?: session.tunnelRecoveryRetryCount
            val retryCount = telemetry?.retryCount() ?: session.retryCount()
            val fields =
                buildList {
                    failureClass?.let { add(DiagnosticsFieldUiModel("Failure class", it)) }
                    winningStrategyFamily?.let { add(DiagnosticsFieldUiModel("Winning strategy", it)) }
                    winningTcpStrategyFamily?.let { add(DiagnosticsFieldUiModel("Winning TCP family", it)) }
                    winningQuicStrategyFamily?.let { add(DiagnosticsFieldUiModel("Winning QUIC family", it)) }
                    telemetryNetworkFingerprintHash?.let {
                        add(DiagnosticsFieldUiModel("Network fingerprint", formatTelemetryHash(it)))
                    }
                    add(DiagnosticsFieldUiModel("Proxy RTT band", proxyRttBand))
                    add(DiagnosticsFieldUiModel("Resolver RTT band", resolverRttBand))
                    add(DiagnosticsFieldUiModel("Aggregate RTT band", rttBand))
                    add(DiagnosticsFieldUiModel("Proxy route retries", proxyRouteRetryCount.toString()))
                    add(DiagnosticsFieldUiModel("Tunnel recovery retries", tunnelRecoveryRetryCount.toString()))
                    add(DiagnosticsFieldUiModel("Total retries", retryCount.toString()))
                }
            return fields.takeIf { it.isNotEmpty() }?.let {
                DiagnosticsContextGroupUiModel(title = "Field telemetry", fields = it)
            }
        }

        private fun formatTelemetryHash(value: String): String =
            if (value.length <= 24) value else "${value.take(12)}...${value.takeLast(8)}"

        private fun toneForConnection(session: DiagnosticConnectionSession): DiagnosticsTone =
            when {
                session.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsTone.Negative
                session.health.equals("degraded", ignoreCase = true) -> DiagnosticsTone.Warning
                session.finishedAt == null -> DiagnosticsTone.Positive
                else -> DiagnosticsTone.Neutral
            }

        private fun formatDurationMs(durationMs: Long): String {
            val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L -> "${hours}h ${minutes}m"
                minutes > 0L -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }

internal fun HistoryConnectionRowUiModel.matchesQuery(query: String): Boolean {
    if (query.isBlank()) {
        return true
    }
    val normalized = query.lowercase(Locale.US)
    return listOf(title, subtitle, summary, serviceMode, connectionState, networkType).any {
        it.lowercase(Locale.US).contains(normalized)
    }
}
