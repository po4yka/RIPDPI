package com.poyka.ripdpi.activities

import android.content.Context
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.rttBand
import com.poyka.ripdpi.diagnostics.winningStrategyFamily
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

internal class HistoryConnectionDetailUiFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
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
                rememberedPolicyBadge =
                    session.rememberedPolicyAudit?.let {
                        context.getString(R.string.history_connection_remembered_policy_badge)
                    },
                metrics =
                    listOf(
                        DiagnosticsMetricUiModel("Duration", formatDurationMs(durationMs)),
                        DiagnosticsMetricUiModel("TX", coreSupport.formatBytes(session.txBytes), DiagnosticsTone.Info),
                        DiagnosticsMetricUiModel(
                            "RX",
                            coreSupport.formatBytes(session.rxBytes),
                            DiagnosticsTone.Positive,
                        ),
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
                highlights = buildConnectionHighlights(detail.session, latestTelemetry),
                contextGroups = buildConnectionContextGroups(detail, latestTelemetry),
                snapshots = detail.snapshots.mapNotNull(::toSnapshotUiModel),
                events = detail.events.map(coreSupport::toEventUiModel),
            )
        }

        private fun buildConnectionHighlights(
            session: DiagnosticConnectionSession,
            latestTelemetry: DiagnosticTelemetrySample?,
        ): List<DiagnosticsMetricUiModel> {
            val retryCount = latestTelemetry?.retryCount() ?: session.retryCount()
            val retryTone = if (retryCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral
            val errorTone = if (session.totalErrors > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral
            return buildList {
                add(DiagnosticsMetricUiModel("Network", session.networkType, DiagnosticsTone.Info))
                add(
                    DiagnosticsMetricUiModel(
                        "Health",
                        session.health.replaceFirstChar { it.uppercase() },
                        toneForConnection(session),
                    ),
                )
                add(DiagnosticsMetricUiModel("TX", coreSupport.formatBytes(session.txBytes), DiagnosticsTone.Info))
                add(DiagnosticsMetricUiModel("RX", coreSupport.formatBytes(session.rxBytes), DiagnosticsTone.Positive))
                add(DiagnosticsMetricUiModel("Errors", session.totalErrors.toString(), errorTone))
                add(DiagnosticsMetricUiModel("Route changes", session.routeChanges.toString(), DiagnosticsTone.Info))
                (latestTelemetry?.failureClass ?: session.failureClass)?.let { failure ->
                    add(DiagnosticsMetricUiModel("Failure class", failure, DiagnosticsTone.Warning))
                }
                (latestTelemetry?.winningStrategyFamily() ?: session.winningStrategyFamily())?.let { strategy ->
                    add(DiagnosticsMetricUiModel("Strategy", strategy, DiagnosticsTone.Positive))
                }
                add(
                    DiagnosticsMetricUiModel(
                        "RTT band",
                        latestTelemetry?.rttBand() ?: session.rttBand(),
                        DiagnosticsTone.Info,
                    ),
                )
                add(DiagnosticsMetricUiModel("Retries", retryCount.toString(), retryTone))
            }
        }

        private fun buildConnectionContextGroups(
            detail: DiagnosticConnectionDetail,
            latestTelemetry: DiagnosticTelemetrySample?,
        ): List<DiagnosticsContextGroupUiModel> =
            buildList {
                addAll(
                    detail.contexts
                        .mapNotNull { it.context }
                        .flatMap { context -> context.toContextGroups() }
                        .distinctBy { group ->
                            group.title + group.fields.joinToString { field -> "${field.label}:${field.value}" }
                        },
                )
                buildFieldTelemetryGroup(detail.session, latestTelemetry)?.let(::add)
                buildRememberedPolicyGroup(detail.session)?.let(::add)
            }

        private fun toSnapshotUiModel(snapshotEntity: DiagnosticNetworkSnapshot): DiagnosticsNetworkSnapshotUiModel? {
            val snapshot = snapshotEntity.snapshot ?: return null
            return DiagnosticsNetworkSnapshotUiModel(
                title = snapshotEntity.snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
                subtitle = "${snapshot.transport} · ${coreSupport.formatTimestamp(snapshot.capturedAt)}",
                fieldGroups =
                    listOf(
                        DiagnosticsFieldGroupUiModel(
                            header = "",
                            fields =
                                listOf(
                                    DiagnosticsFieldUiModel(
                                        "DNS",
                                        snapshot.dnsServers.joinToString().ifBlank { "Unknown" },
                                    ),
                                    DiagnosticsFieldUiModel("Private DNS", snapshot.privateDnsMode),
                                    DiagnosticsFieldUiModel("Public IP", snapshot.publicIp ?: "Unknown"),
                                    DiagnosticsFieldUiModel("Validated", snapshot.networkValidated.toString()),
                                    DiagnosticsFieldUiModel(
                                        "Captive portal",
                                        snapshot.captivePortalDetected.toString(),
                                    ),
                                ),
                        ),
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

        private fun buildRememberedPolicyGroup(session: DiagnosticConnectionSession): DiagnosticsContextGroupUiModel? {
            val audit = session.rememberedPolicyAudit ?: return null
            val fields =
                buildList {
                    add(
                        DiagnosticsFieldUiModel(
                            context.getString(R.string.history_connection_remembered_policy_source),
                            audit.source.displaySourceLabel(context),
                        ),
                    )
                    if (audit.appliedByExactMatch) {
                        add(
                            DiagnosticsFieldUiModel(
                                context.getString(R.string.history_connection_remembered_policy_reason),
                                context.getString(R.string.history_connection_remembered_policy_exact_match),
                            ),
                        )
                    }
                    audit.matchedFingerprintHash.shortFingerprintHash()?.let { fingerprint ->
                        add(
                            DiagnosticsFieldUiModel(
                                context.getString(R.string.history_connection_remembered_policy_fingerprint),
                                fingerprint,
                            ),
                        )
                    }
                    add(
                        DiagnosticsFieldUiModel(
                            context.getString(R.string.history_connection_remembered_policy_previous_successes),
                            audit.previousSuccessCount.toString(),
                        ),
                    )
                    add(
                        DiagnosticsFieldUiModel(
                            context.getString(R.string.history_connection_remembered_policy_previous_failures),
                            audit.previousFailureCount.toString(),
                        ),
                    )
                    add(
                        DiagnosticsFieldUiModel(
                            context.getString(
                                R.string.history_connection_remembered_policy_previous_consecutive_failures,
                            ),
                            audit.previousConsecutiveFailureCount.toString(),
                        ),
                    )
                }
            return DiagnosticsContextGroupUiModel(
                title = context.getString(R.string.history_connection_remembered_policy_section),
                fields = fields,
            )
        }

        private fun formatTelemetryHash(value: String): String =
            if (value.length <= MaxTelemetryHashLength) {
                value
            } else {
                "${value.take(TelemetryHashPrefixLength)}...${value.takeLast(TelemetryHashSuffixLength)}"
            }

        private fun toneForConnection(session: DiagnosticConnectionSession): DiagnosticsTone =
            when {
                session.connectionState.equals("failed", ignoreCase = true) -> DiagnosticsTone.Negative
                session.health.equals("degraded", ignoreCase = true) -> DiagnosticsTone.Warning
                session.finishedAt == null -> DiagnosticsTone.Positive
                else -> DiagnosticsTone.Neutral
            }

        private fun formatDurationMs(durationMs: Long): String {
            val totalSeconds = (durationMs / MillisecondsPerSecond).coerceAtLeast(0L)
            val hours = totalSeconds / SecondsPerHour
            val minutes = (totalSeconds % SecondsPerHour) / SecondsPerMinute
            val seconds = totalSeconds % SecondsPerMinute
            return when {
                hours > 0L -> "${hours}h ${minutes}m"
                minutes > 0L -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }

private const val MillisecondsPerSecond = 1_000L
private const val SecondsPerMinute = 60L
private const val SecondsPerHour = 3_600L
private const val MaxTelemetryHashLength = 24
private const val TelemetryHashPrefixLength = 12
private const val TelemetryHashSuffixLength = 8

internal fun HistoryConnectionRowUiModel.matchesQuery(query: String): Boolean {
    if (query.isBlank()) {
        return true
    }
    val normalized = query.lowercase(Locale.US)
    return listOfNotNull(
        title,
        subtitle,
        summary,
        serviceMode,
        connectionState,
        networkType,
        rememberedPolicyBadge,
    ).any {
        it.lowercase(Locale.US).contains(normalized)
    }
}
