package com.poyka.ripdpi.activities

import android.content.Context
import androidx.annotation.StringRes
import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample
import com.poyka.ripdpi.diagnostics.retryCount
import com.poyka.ripdpi.diagnostics.rttBand
import com.poyka.ripdpi.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.platform.StringResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale
import javax.inject.Inject

internal class HistoryConnectionDetailUiFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val stringResolver: StringResolver,
        private val coreSupport: DiagnosticsUiCoreSupport,
    ) {
        fun toConnectionRowUiModel(session: DiagnosticConnectionSession): HistoryConnectionRowUiModel {
            val durationMs =
                (session.finishedAt ?: session.updatedAt).coerceAtLeast(session.startedAt) - session.startedAt
            val summary =
                session.failureMessage
                    ?: session.endedReason
                    ?: stringResolver.getString(
                        R.string.history_connection_summary_format,
                        session.serviceMode,
                        session.networkType,
                    )
            return HistoryConnectionRowUiModel(
                id = session.id,
                title =
                    stringResolver.getString(
                        R.string.history_connection_title_format,
                        session.serviceMode,
                        session.connectionState.lowercase(Locale.US),
                    ),
                subtitle =
                    stringResolver.getString(
                        R.string.history_connection_subtitle_format,
                        session.networkType,
                        coreSupport.formatTimestamp(session.startedAt),
                    ),
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
                    persistentListOf(
                        DiagnosticsMetricUiModel(
                            stringResolver.getString(R.string.diagnostics_metric_duration),
                            formatDurationMs(durationMs),
                        ),
                        DiagnosticsMetricUiModel(
                            stringResolver.getString(R.string.diagnostics_metric_tx),
                            coreSupport.formatBytes(session.txBytes),
                            DiagnosticsTone.Info,
                        ),
                        DiagnosticsMetricUiModel(
                            stringResolver.getString(R.string.diagnostics_metric_rx),
                            coreSupport.formatBytes(session.rxBytes),
                            DiagnosticsTone.Positive,
                        ),
                        DiagnosticsMetricUiModel(
                            stringResolver.getString(R.string.diagnostics_metric_errors),
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
                contextGroups = buildConnectionContextGroups(detail, latestTelemetry).toImmutableList(),
                snapshots = detail.snapshots.mapNotNull(::toSnapshotUiModel).toImmutableList(),
                events = detail.events.map(coreSupport::toEventUiModel).toImmutableList(),
            )
        }

        private fun buildConnectionHighlights(
            session: DiagnosticConnectionSession,
            latestTelemetry: DiagnosticTelemetrySample?,
        ): ImmutableList<DiagnosticsMetricUiModel> {
            val retryCount = latestTelemetry?.retryCount() ?: session.retryCount()
            val retryTone = if (retryCount > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral
            val errorTone = if (session.totalErrors > 0) DiagnosticsTone.Warning else DiagnosticsTone.Neutral
            return buildList {
                add(metric(R.string.diagnostics_metric_network, session.networkType, DiagnosticsTone.Info))
                add(
                    metric(
                        R.string.diagnostics_metric_health,
                        session.health.replaceFirstChar { it.uppercase() },
                        toneForConnection(session),
                    ),
                )
                add(
                    metric(
                        R.string.diagnostics_metric_tx,
                        coreSupport.formatBytes(session.txBytes),
                        DiagnosticsTone.Info,
                    ),
                )
                add(
                    metric(
                        R.string.diagnostics_metric_rx,
                        coreSupport.formatBytes(session.rxBytes),
                        DiagnosticsTone.Positive,
                    ),
                )
                add(metric(R.string.diagnostics_metric_errors, session.totalErrors.toString(), errorTone))
                add(
                    metric(
                        R.string.diagnostics_metric_route_changes,
                        session.routeChanges.toString(),
                        DiagnosticsTone.Info,
                    ),
                )
                (latestTelemetry?.failureClass ?: session.failureClass)?.let { failure ->
                    add(metric(R.string.diagnostics_metric_failure_class, failure, DiagnosticsTone.Warning))
                }
                (latestTelemetry?.winningStrategyFamily() ?: session.winningStrategyFamily())?.let { strategy ->
                    add(metric(R.string.diagnostics_metric_strategy, strategy, DiagnosticsTone.Positive))
                }
                add(
                    DiagnosticsMetricUiModel(
                        stringResolver.getString(R.string.diagnostics_metric_rtt_band),
                        latestTelemetry?.rttBand() ?: session.rttBand(),
                        DiagnosticsTone.Info,
                    ),
                )
                add(metric(R.string.diagnostics_metric_retries, retryCount.toString(), retryTone))
            }.toImmutableList()
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
                subtitle =
                    stringResolver.getString(
                        R.string.history_connection_subtitle_format,
                        snapshot.transport,
                        coreSupport.formatTimestamp(snapshot.capturedAt),
                    ),
                fieldGroups =
                    listOf(
                        DiagnosticsFieldGroupUiModel(
                            header = "",
                            fields =
                                listOf(
                                    DiagnosticsFieldUiModel(
                                        stringResolver.getString(R.string.diagnostics_field_dns),
                                        snapshot.dnsServers.joinToString().ifBlank {
                                            stringResolver.getString(R.string.diagnostics_field_unknown)
                                        },
                                    ),
                                    DiagnosticsFieldUiModel(
                                        stringResolver.getString(R.string.diagnostics_field_private_dns),
                                        snapshot.privateDnsMode,
                                    ),
                                    DiagnosticsFieldUiModel(
                                        stringResolver.getString(R.string.diagnostics_field_public_ip),
                                        snapshot.publicIp
                                            ?: stringResolver.getString(R.string.diagnostics_field_unknown),
                                    ),
                                    DiagnosticsFieldUiModel(
                                        stringResolver.getString(R.string.diagnostics_field_validated),
                                        snapshot.networkValidated.toString(),
                                    ),
                                    DiagnosticsFieldUiModel(
                                        stringResolver.getString(R.string.diagnostics_field_captive_portal),
                                        snapshot.captivePortalDetected.toString(),
                                    ),
                                ).toImmutableList(),
                        ),
                    ).toImmutableList(),
            )
        }

        private fun DiagnosticContextModel.toContextGroups(): List<DiagnosticsContextGroupUiModel> =
            listOf(
                DiagnosticsContextGroupUiModel(
                    title = context.getString(R.string.history_detail_section_service),
                    fields =
                        listOf(
                            field(R.string.diagnostics_field_status, service.serviceStatus),
                            field(R.string.diagnostics_field_mode, service.activeMode),
                            field(R.string.diagnostics_field_profile, service.selectedProfileName),
                            field(R.string.diagnostics_field_config_source, service.configSource),
                            field(R.string.diagnostics_field_proxy, service.proxyEndpoint),
                            field(R.string.diagnostics_field_chain, service.chainSummary),
                            field(R.string.diagnostics_field_last_native_error, service.lastNativeErrorHeadline),
                        ).toImmutableList(),
                ),
                DiagnosticsContextGroupUiModel(
                    title = context.getString(R.string.history_detail_section_environment),
                    fields =
                        listOf(
                            field(R.string.diagnostics_field_vpn_permission, permissions.vpnPermissionState),
                            field(
                                R.string.diagnostics_field_notification_permission,
                                permissions.notificationPermissionState,
                            ),
                            field(
                                R.string.diagnostics_field_battery_optimization,
                                permissions.batteryOptimizationState,
                            ),
                            field(R.string.diagnostics_field_data_saver, permissions.dataSaverState),
                            field(R.string.diagnostics_field_power_save, environment.powerSaveModeState),
                            field(R.string.diagnostics_field_metered, environment.networkMeteredState),
                            field(R.string.diagnostics_field_roaming, environment.roamingState),
                        ).toImmutableList(),
                ),
                DiagnosticsContextGroupUiModel(
                    title = context.getString(R.string.history_detail_section_device),
                    fields =
                        listOf(
                            field(R.string.diagnostics_field_app, device.appVersionName),
                            field(R.string.diagnostics_field_device, "${device.manufacturer} ${device.model}"),
                            field(
                                R.string.diagnostics_field_android,
                                stringResolver.getString(
                                    R.string.diagnostics_field_android_version_format,
                                    device.androidVersion,
                                    device.apiLevel,
                                ),
                            ),
                            field(R.string.diagnostics_field_locale, device.locale),
                        ).toImmutableList(),
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
                    failureClass?.let {
                        add(field(R.string.diagnostics_metric_failure_class, it))
                    }
                    winningStrategyFamily?.let {
                        add(field(R.string.diagnostics_metric_winning_strategy, it))
                    }
                    winningTcpStrategyFamily?.let {
                        add(field(R.string.diagnostics_field_winning_tcp_family, it))
                    }
                    winningQuicStrategyFamily?.let {
                        add(field(R.string.diagnostics_field_winning_quic_family, it))
                    }
                    telemetryNetworkFingerprintHash?.let {
                        add(field(R.string.diagnostics_field_network_fingerprint, formatTelemetryHash(it)))
                    }
                    add(field(R.string.diagnostics_field_proxy_rtt_band, proxyRttBand))
                    add(field(R.string.diagnostics_field_resolver_rtt_band, resolverRttBand))
                    add(field(R.string.diagnostics_field_aggregate_rtt_band, rttBand))
                    add(field(R.string.diagnostics_field_proxy_route_retries, proxyRouteRetryCount.toString()))
                    add(field(R.string.diagnostics_field_tunnel_recovery_retries, tunnelRecoveryRetryCount.toString()))
                    add(field(R.string.diagnostics_field_total_retries, retryCount.toString()))
                }
            return fields.takeIf { it.isNotEmpty() }?.let {
                DiagnosticsContextGroupUiModel(
                    title = context.getString(R.string.history_detail_section_field_telemetry),
                    fields = it.toImmutableList(),
                )
            }
        }

        private fun buildRememberedPolicyGroup(session: DiagnosticConnectionSession): DiagnosticsContextGroupUiModel? {
            val audit = session.rememberedPolicyAudit ?: return null
            val fields =
                buildList {
                    add(
                        DiagnosticsFieldUiModel(
                            context.getString(R.string.history_connection_remembered_policy_source),
                            audit.source.displaySourceLabel(stringResolver),
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
                fields = fields.toImmutableList(),
            )
        }

        private fun formatTelemetryHash(value: String): String =
            if (value.length <= MaxTelemetryHashLength) {
                value
            } else {
                "${value.take(TelemetryHashPrefixLength)}...${value.takeLast(TelemetryHashSuffixLength)}"
            }

        private fun metric(
            @StringRes labelRes: Int,
            value: String,
            tone: DiagnosticsTone = DiagnosticsTone.Neutral,
        ): DiagnosticsMetricUiModel = DiagnosticsMetricUiModel(stringResolver.getString(labelRes), value, tone)

        private fun field(
            @StringRes labelRes: Int,
            value: String,
        ): DiagnosticsFieldUiModel = DiagnosticsFieldUiModel(stringResolver.getString(labelRes), value)

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
                hours > 0L -> stringResolver.getString(R.string.diagnostics_duration_hours_format, hours, minutes)
                minutes > 0L -> stringResolver.getString(R.string.diagnostics_duration_minutes_format, minutes, seconds)
                else -> stringResolver.getString(R.string.diagnostics_duration_seconds_format, seconds)
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
