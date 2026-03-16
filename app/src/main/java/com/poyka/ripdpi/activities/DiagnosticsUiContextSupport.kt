package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.permissions.BatteryOptimizationGuidance
import java.util.Locale

internal fun DiagnosticsUiFactorySupport.toNetworkSnapshotUiModel(
    entity: NetworkSnapshotEntity,
    showSensitiveDetails: Boolean,
): DiagnosticsNetworkSnapshotUiModel? {
    val snapshot =
        runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), entity.payloadJson) }.getOrNull()
            ?: return null
    return DiagnosticsNetworkSnapshotUiModel(
        title = entity.snapshotKind.replace('_', ' ').replaceFirstChar { it.uppercase() },
        subtitle = "${snapshot.transport} · ${formatTimestamp(snapshot.capturedAt)}",
        fields =
            listOf(
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_capabilities),
                    snapshot.capabilities.joinToString()
                        .ifBlank { context.getString(R.string.diagnostics_field_unknown) }),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_dns),
                    if (showSensitiveDetails) snapshot.dnsServers.joinToString()
                        .ifBlank { context.getString(R.string.diagnostics_field_unknown) } else redactCollection(
                        snapshot.dnsServers
                    )),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_private_dns),
                    snapshot.privateDnsMode
                ),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_mtu),
                    snapshot.mtu?.toString() ?: context.getString(R.string.diagnostics_field_unknown)
                ),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_local),
                    if (showSensitiveDetails) snapshot.localAddresses.joinToString()
                        .ifBlank { context.getString(R.string.diagnostics_field_unknown) } else redactCollection(
                        snapshot.localAddresses
                    )),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_public_ip),
                    if (showSensitiveDetails) snapshot.publicIp
                        ?: context.getString(R.string.diagnostics_field_unknown) else redactValue(snapshot.publicIp)
                ),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_asn),
                    snapshot.publicAsn ?: context.getString(R.string.diagnostics_field_unknown)
                ),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_validated),
                    snapshot.networkValidated.toString()
                ),
                DiagnosticsFieldUiModel(
                    context.getString(R.string.diagnostics_field_captive_portal),
                    snapshot.captivePortalDetected.toString()
                ),
            ) + transportSpecificFields(snapshot, showSensitiveDetails),
    )
}

internal fun DiagnosticsUiFactorySupport.toOverviewContextGroup(
    context: DiagnosticContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = this.context.getString(R.string.diagnostics_field_support_context),
        fields =
            listOf(
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_app),
                    context.device.appVersionName
                ),
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_device),
                    "${context.device.manufacturer} ${context.device.model}"
                ),
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_android),
                    this.context.getString(
                        R.string.diagnostics_field_android_version_format,
                        context.device.androidVersion,
                        context.device.apiLevel
                    )
                ),
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_mode),
                    context.service.activeMode
                ),
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_profile),
                    context.service.selectedProfileName
                ),
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_host_learning),
                    buildHostAutolearnOverviewSummary(context.service)
                ),
                DiagnosticsFieldUiModel(
                    this.context.getString(R.string.diagnostics_field_restrictions),
                    listOf(
                        context.permissions.dataSaverState,
                        context.environment.powerSaveModeState
                    ).joinToString(" · "),
                ),
            ),
    )

internal fun DiagnosticsUiFactorySupport.toLiveContextGroups(
    context: DiagnosticContextModel,
): List<DiagnosticsContextGroupUiModel> =
    listOf(
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_service),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_status),
                        context.service.serviceStatus
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_mode),
                        context.service.activeMode
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_profile),
                        context.service.selectedProfileName
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_uptime),
                        context.service.sessionUptimeMs?.let(::formatDurationMs)
                            ?: this.context.getString(R.string.diagnostics_field_unknown)
                    ),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_environment),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_data_saver),
                        context.permissions.dataSaverState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_power_save),
                        context.environment.powerSaveModeState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_metered),
                        context.environment.networkMeteredState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_roaming),
                        context.environment.roamingState
                    ),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_host_learning),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_enabled),
                        formatAutolearnState(context.service.hostAutolearnEnabled)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_learned_hosts),
                        context.service.learnedHostCount.toString()
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_penalized),
                        context.service.penalizedHostCount.toString()
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_host),
                        formatAutolearnHost(context.service.lastAutolearnHost)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_group),
                        formatAutolearnGroup(context.service.lastAutolearnGroup)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_action),
                        formatAutolearnAction(context.service.lastAutolearnAction)
                    ),
                ),
        ),
    )

internal fun DiagnosticsUiFactorySupport.buildContextWarnings(
    context: DiagnosticContextModel?,
): List<DiagnosticsEventUiModel> {
    if (context == null) {
        return emptyList()
    }
    val warnings = mutableListOf<DiagnosticsEventUiModel>()
    if (context.permissions.vpnPermissionState == "disabled") {
        warnings +=
            DiagnosticsEventUiModel(
                id = "context-vpn-permission",
                source = "Context",
                severity = "WARN",
                message = this.context.getString(R.string.diagnostics_warn_vpn_permission),
                createdAtLabel = "now",
                tone = DiagnosticsTone.Warning,
            )
    }
    if (context.permissions.notificationPermissionState == "disabled") {
        warnings +=
            DiagnosticsEventUiModel(
                id = "context-notification-permission",
                source = "Context",
                severity = "WARN",
                message = this.context.getString(R.string.diagnostics_warn_notification_permission),
                createdAtLabel = "now",
                tone = DiagnosticsTone.Warning,
            )
    }
    val hasPowerRestriction =
        context.permissions.dataSaverState == "enabled" || context.environment.powerSaveModeState == "enabled"
    val samsungWarningRes = BatteryOptimizationGuidance.diagnosticsWarningRes(context.device.manufacturer)
    if (hasPowerRestriction) {
        warnings +=
            DiagnosticsEventUiModel(
                id = "context-power-restriction",
                source = "Context",
                severity = "WARN",
                message =
                    samsungWarningRes?.let(this.context::getString)
                        ?: this.context.getString(R.string.diagnostics_warn_power_restriction),
                createdAtLabel = "now",
                tone = DiagnosticsTone.Warning,
            )
    }
    if (samsungWarningRes != null && context.permissions.batteryOptimizationState != "enabled" && !hasPowerRestriction) {
        warnings +=
            DiagnosticsEventUiModel(
                id = "context-samsung-background-limits",
                source = "Context",
                severity = "WARN",
                message = this.context.getString(samsungWarningRes),
                createdAtLabel = "now",
                tone = DiagnosticsTone.Warning,
            )
    }
    return warnings
}

internal fun DiagnosticsUiFactorySupport.toContextUiGroups(
    context: DiagnosticContextModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsContextGroupUiModel> =
    listOf(
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_service),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_status),
                        context.service.serviceStatus
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_configured_mode),
                        context.service.configuredMode
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_active_mode),
                        context.service.activeMode
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_profile),
                        context.service.selectedProfileName
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_config_source),
                        context.service.configSource
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_proxy),
                        if (showSensitiveDetails) context.service.proxyEndpoint else redactValue(context.service.proxyEndpoint)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_chain),
                        context.service.chainSummary
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_desync),
                        context.service.desyncMethod
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_route_group),
                        context.service.routeGroup
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_autolearn),
                        formatAutolearnState(context.service.hostAutolearnEnabled)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_learned_hosts),
                        context.service.learnedHostCount.toString()
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_penalized_hosts),
                        context.service.penalizedHostCount.toString()
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_learned_host),
                        formatAutolearnHost(context.service.lastAutolearnHost)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_learned_group),
                        formatAutolearnGroup(context.service.lastAutolearnGroup)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_autolearn_action),
                        formatAutolearnAction(context.service.lastAutolearnAction)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_restart_count),
                        context.service.restartCount.toString()
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_uptime),
                        context.service.sessionUptimeMs?.let(::formatDurationMs)
                            ?: this.context.getString(R.string.diagnostics_field_unknown)
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_last_native_error),
                        context.service.lastNativeErrorHeadline
                    ),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_permissions),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_vpn_permission),
                        context.permissions.vpnPermissionState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_notification_permission),
                        context.permissions.notificationPermissionState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_battery_optimization),
                        context.permissions.batteryOptimizationState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_data_saver),
                        context.permissions.dataSaverState
                    ),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_device),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_app_version),
                        this.context.getString(
                            R.string.diagnostics_field_app_version_format,
                            context.device.appVersionName,
                            context.device.buildType
                        )
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_version_code),
                        context.device.appVersionCode.toString()
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_device),
                        "${context.device.manufacturer} ${context.device.model}"
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_android),
                        this.context.getString(
                            R.string.diagnostics_field_android_version_format,
                            context.device.androidVersion,
                            context.device.apiLevel
                        )
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_abi),
                        context.device.primaryAbi
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_locale),
                        context.device.locale
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_timezone),
                        context.device.timezone
                    ),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = this.context.getString(R.string.diagnostics_field_environment),
            fields =
                listOf(
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_battery_saver),
                        context.environment.batterySaverState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_power_save),
                        context.environment.powerSaveModeState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_network_metered),
                        context.environment.networkMeteredState
                    ),
                    DiagnosticsFieldUiModel(
                        this.context.getString(R.string.diagnostics_field_roaming),
                        context.environment.roamingState
                    ),
                ),
        ),
    )

private fun DiagnosticsUiFactorySupport.transportSpecificFields(
    snapshot: NetworkSnapshotModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    buildList {
        val ctx = context
        val unknown = ctx.getString(R.string.diagnostics_field_unknown)
        snapshot.wifiDetails?.let { wifi ->
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_ssid),
                    if (showSensitiveDetails) wifi.ssid else redactValue(wifi.ssid.takeUnless { it == "unknown" })
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_bssid),
                    if (showSensitiveDetails) wifi.bssid else redactValue(wifi.bssid.takeUnless { it == "unknown" })
                )
            )
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_wifi_band), wifi.band))
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_wifi_standard), wifi.wifiStandard))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_frequency),
                    wifi.frequencyMhz?.let { ctx.getString(R.string.diagnostics_field_wifi_frequency_format, it) }
                        ?: unknown))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_channel_width),
                    wifi.channelWidth
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_rssi),
                    wifi.rssiDbm?.let { ctx.getString(R.string.diagnostics_field_wifi_rssi_format, it) } ?: unknown))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_link),
                    wifi.linkSpeedMbps?.let { ctx.getString(R.string.diagnostics_field_wifi_speed_format, it) }
                        ?: unknown))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_rx_link),
                    wifi.rxLinkSpeedMbps?.let { ctx.getString(R.string.diagnostics_field_wifi_speed_format, it) }
                        ?: unknown))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_tx_link),
                    wifi.txLinkSpeedMbps?.let { ctx.getString(R.string.diagnostics_field_wifi_speed_format, it) }
                        ?: unknown))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_hidden_ssid),
                    wifi.hiddenSsid?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_passpoint),
                    wifi.isPasspoint?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_osu_ap),
                    wifi.isOsuAp?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_network_id),
                    wifi.networkId?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_gateway),
                    if (showSensitiveDetails) wifi.gateway ?: unknown else redactValue(wifi.gateway)
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_dhcp_server),
                    if (showSensitiveDetails) wifi.dhcpServer ?: unknown else redactValue(wifi.dhcpServer)
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_ip),
                    if (showSensitiveDetails) wifi.ipAddress ?: unknown else redactValue(wifi.ipAddress)
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_subnet),
                    if (showSensitiveDetails) wifi.subnetMask ?: unknown else redactValue(wifi.subnetMask)
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_wifi_lease),
                    wifi.leaseDurationSeconds?.let { ctx.getString(R.string.diagnostics_field_wifi_lease_format, it) }
                        ?: unknown))
        }
        snapshot.cellularDetails?.let { cellular ->
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_carrier), cellular.carrierName))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_sim_operator),
                    cellular.simOperatorName
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_network_operator),
                    cellular.networkOperatorName
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_network_country),
                    cellular.networkCountryIso
                )
            )
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_sim_country), cellular.simCountryIso))
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_operator_code), cellular.operatorCode))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_sim_operator_code),
                    cellular.simOperatorCode
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_data_network),
                    cellular.dataNetworkType
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_voice_network),
                    cellular.voiceNetworkType
                )
            )
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_data_state), cellular.dataState))
            add(DiagnosticsFieldUiModel(ctx.getString(R.string.diagnostics_field_service_state), cellular.serviceState))
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_roaming),
                    cellular.isNetworkRoaming?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_carrier_id),
                    cellular.carrierId?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_sim_carrier_id),
                    cellular.simCarrierId?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_signal_level),
                    cellular.signalLevel?.toString() ?: unknown
                )
            )
            add(
                DiagnosticsFieldUiModel(
                    ctx.getString(R.string.diagnostics_field_signal_dbm),
                    cellular.signalDbm?.let { ctx.getString(R.string.diagnostics_field_signal_dbm_format, it) }
                        ?: unknown))
        }
    }

private fun DiagnosticsUiFactorySupport.buildHostAutolearnOverviewSummary(
    service: com.poyka.ripdpi.diagnostics.ServiceContextModel,
): String {
    val state = formatAutolearnState(service.hostAutolearnEnabled)
    val details =
        buildList {
            if (service.learnedHostCount > 0) {
                add(context.getString(R.string.diagnostics_autolearn_learned_format, service.learnedHostCount))
            }
            if (service.penalizedHostCount > 0) {
                add(context.getString(R.string.diagnostics_autolearn_penalized_format, service.penalizedHostCount))
            }
        }
    return if (details.isEmpty()) state else "$state · ${details.joinToString(" · ")}"
}

private fun DiagnosticsUiFactorySupport.formatAutolearnState(value: String): String =
    when (value.lowercase(Locale.US)) {
        "enabled" -> context.getString(R.string.diagnostics_autolearn_active)
        "disabled" -> context.getString(R.string.diagnostics_autolearn_off)
        else -> context.getString(R.string.diagnostics_field_unknown)
    }

private fun DiagnosticsUiFactorySupport.formatAutolearnHost(value: String): String =
    value.takeUnless { it.isBlank() || it == "none" } ?: context.getString(R.string.diagnostics_autolearn_none_yet)

private fun DiagnosticsUiFactorySupport.formatAutolearnGroup(value: String): String =
    value.takeUnless { it.isBlank() || it == "none" }
        ?.let { context.getString(R.string.diagnostics_autolearn_route_format, it) }
        ?: context.getString(R.string.diagnostics_autolearn_none_yet)

private fun DiagnosticsUiFactorySupport.formatAutolearnAction(value: String): String =
    when (value.lowercase(Locale.US)) {
        "host_promoted" -> context.getString(R.string.diagnostics_autolearn_promoted)
        "group_penalized" -> context.getString(R.string.diagnostics_autolearn_penalized)
        "store_reset" -> context.getString(R.string.diagnostics_autolearn_store_reset)
        "none", "" -> context.getString(R.string.diagnostics_autolearn_none_yet)
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }
