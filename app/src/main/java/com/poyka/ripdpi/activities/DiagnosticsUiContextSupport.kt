package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
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
                DiagnosticsFieldUiModel("Capabilities", snapshot.capabilities.joinToString().ifBlank { "Unknown" }),
                DiagnosticsFieldUiModel("DNS", if (showSensitiveDetails) snapshot.dnsServers.joinToString().ifBlank { "Unknown" } else redactCollection(snapshot.dnsServers)),
                DiagnosticsFieldUiModel("Private DNS", snapshot.privateDnsMode),
                DiagnosticsFieldUiModel("MTU", snapshot.mtu?.toString() ?: "Unknown"),
                DiagnosticsFieldUiModel("Local", if (showSensitiveDetails) snapshot.localAddresses.joinToString().ifBlank { "Unknown" } else redactCollection(snapshot.localAddresses)),
                DiagnosticsFieldUiModel("Public IP", if (showSensitiveDetails) snapshot.publicIp ?: "Unknown" else redactValue(snapshot.publicIp)),
                DiagnosticsFieldUiModel("ASN", snapshot.publicAsn ?: "Unknown"),
                DiagnosticsFieldUiModel("Validated", snapshot.networkValidated.toString()),
                DiagnosticsFieldUiModel("Captive portal", snapshot.captivePortalDetected.toString()),
            ) + transportSpecificFields(snapshot, showSensitiveDetails),
    )
}

internal fun DiagnosticsUiFactorySupport.toOverviewContextGroup(
    context: DiagnosticContextModel,
): DiagnosticsContextGroupUiModel =
    DiagnosticsContextGroupUiModel(
        title = "Support context",
        fields =
            listOf(
                DiagnosticsFieldUiModel("App", context.device.appVersionName),
                DiagnosticsFieldUiModel("Device", "${context.device.manufacturer} ${context.device.model}"),
                DiagnosticsFieldUiModel("Android", "${context.device.androidVersion} (API ${context.device.apiLevel})"),
                DiagnosticsFieldUiModel("Mode", context.service.activeMode),
                DiagnosticsFieldUiModel("Profile", context.service.selectedProfileName),
                DiagnosticsFieldUiModel("Host learning", buildHostAutolearnOverviewSummary(context.service)),
                DiagnosticsFieldUiModel(
                    "Restrictions",
                    listOf(context.permissions.dataSaverState, context.environment.powerSaveModeState).joinToString(" · "),
                ),
            ),
    )

internal fun DiagnosticsUiFactorySupport.toLiveContextGroups(
    context: DiagnosticContextModel,
): List<DiagnosticsContextGroupUiModel> =
    listOf(
        DiagnosticsContextGroupUiModel(
            title = "Service",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("Status", context.service.serviceStatus),
                    DiagnosticsFieldUiModel("Mode", context.service.activeMode),
                    DiagnosticsFieldUiModel("Profile", context.service.selectedProfileName),
                    DiagnosticsFieldUiModel("Uptime", context.service.sessionUptimeMs?.let(::formatDurationMs) ?: "Unknown"),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = "Environment",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("Data saver", context.permissions.dataSaverState),
                    DiagnosticsFieldUiModel("Power save", context.environment.powerSaveModeState),
                    DiagnosticsFieldUiModel("Metered", context.environment.networkMeteredState),
                    DiagnosticsFieldUiModel("Roaming", context.environment.roamingState),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = "Host learning",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("Enabled", formatAutolearnState(context.service.hostAutolearnEnabled)),
                    DiagnosticsFieldUiModel("Learned hosts", context.service.learnedHostCount.toString()),
                    DiagnosticsFieldUiModel("Penalized", context.service.penalizedHostCount.toString()),
                    DiagnosticsFieldUiModel("Last host", formatAutolearnHost(context.service.lastAutolearnHost)),
                    DiagnosticsFieldUiModel("Last group", formatAutolearnGroup(context.service.lastAutolearnGroup)),
                    DiagnosticsFieldUiModel("Last action", formatAutolearnAction(context.service.lastAutolearnAction)),
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
                message = "VPN permission is not currently granted.",
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
                message = "Notification permission is disabled, so service issues may be harder to notice.",
                createdAtLabel = "now",
                tone = DiagnosticsTone.Warning,
            )
    }
    if (context.permissions.dataSaverState == "enabled" || context.environment.powerSaveModeState == "enabled") {
        warnings +=
            DiagnosticsEventUiModel(
                id = "context-power-restriction",
                source = "Context",
                severity = "WARN",
                message = "Power or background restrictions may interfere with stable diagnostics.",
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
            title = "Service",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("Status", context.service.serviceStatus),
                    DiagnosticsFieldUiModel("Configured mode", context.service.configuredMode),
                    DiagnosticsFieldUiModel("Active mode", context.service.activeMode),
                    DiagnosticsFieldUiModel("Profile", context.service.selectedProfileName),
                    DiagnosticsFieldUiModel("Config source", context.service.configSource),
                    DiagnosticsFieldUiModel("Proxy", if (showSensitiveDetails) context.service.proxyEndpoint else redactValue(context.service.proxyEndpoint)),
                    DiagnosticsFieldUiModel("Chain", context.service.chainSummary),
                    DiagnosticsFieldUiModel("Desync", context.service.desyncMethod),
                    DiagnosticsFieldUiModel("Route group", context.service.routeGroup),
                    DiagnosticsFieldUiModel("Autolearn", formatAutolearnState(context.service.hostAutolearnEnabled)),
                    DiagnosticsFieldUiModel("Learned hosts", context.service.learnedHostCount.toString()),
                    DiagnosticsFieldUiModel("Penalized hosts", context.service.penalizedHostCount.toString()),
                    DiagnosticsFieldUiModel("Last learned host", formatAutolearnHost(context.service.lastAutolearnHost)),
                    DiagnosticsFieldUiModel("Last learned group", formatAutolearnGroup(context.service.lastAutolearnGroup)),
                    DiagnosticsFieldUiModel("Last autolearn action", formatAutolearnAction(context.service.lastAutolearnAction)),
                    DiagnosticsFieldUiModel("Restart count", context.service.restartCount.toString()),
                    DiagnosticsFieldUiModel("Uptime", context.service.sessionUptimeMs?.let(::formatDurationMs) ?: "Unknown"),
                    DiagnosticsFieldUiModel("Last native error", context.service.lastNativeErrorHeadline),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = "Permissions",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("VPN permission", context.permissions.vpnPermissionState),
                    DiagnosticsFieldUiModel("Notification permission", context.permissions.notificationPermissionState),
                    DiagnosticsFieldUiModel("Battery optimization", context.permissions.batteryOptimizationState),
                    DiagnosticsFieldUiModel("Data saver", context.permissions.dataSaverState),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = "Device",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("App version", "${context.device.appVersionName} (${context.device.buildType})"),
                    DiagnosticsFieldUiModel("Version code", context.device.appVersionCode.toString()),
                    DiagnosticsFieldUiModel("Device", "${context.device.manufacturer} ${context.device.model}"),
                    DiagnosticsFieldUiModel("Android", "${context.device.androidVersion} (API ${context.device.apiLevel})"),
                    DiagnosticsFieldUiModel("ABI", context.device.primaryAbi),
                    DiagnosticsFieldUiModel("Locale", context.device.locale),
                    DiagnosticsFieldUiModel("Timezone", context.device.timezone),
                ),
        ),
        DiagnosticsContextGroupUiModel(
            title = "Environment",
            fields =
                listOf(
                    DiagnosticsFieldUiModel("Battery saver", context.environment.batterySaverState),
                    DiagnosticsFieldUiModel("Power save", context.environment.powerSaveModeState),
                    DiagnosticsFieldUiModel("Network metered", context.environment.networkMeteredState),
                    DiagnosticsFieldUiModel("Roaming", context.environment.roamingState),
                ),
        ),
    )

private fun DiagnosticsUiFactorySupport.transportSpecificFields(
    snapshot: NetworkSnapshotModel,
    showSensitiveDetails: Boolean,
): List<DiagnosticsFieldUiModel> =
    buildList {
        snapshot.wifiDetails?.let { wifi ->
            add(DiagnosticsFieldUiModel("Wi-Fi SSID", if (showSensitiveDetails) wifi.ssid else redactValue(wifi.ssid.takeUnless { it == "unknown" })))
            add(DiagnosticsFieldUiModel("Wi-Fi BSSID", if (showSensitiveDetails) wifi.bssid else redactValue(wifi.bssid.takeUnless { it == "unknown" })))
            add(DiagnosticsFieldUiModel("Wi-Fi band", wifi.band))
            add(DiagnosticsFieldUiModel("Wi-Fi standard", wifi.wifiStandard))
            add(DiagnosticsFieldUiModel("Wi-Fi frequency", wifi.frequencyMhz?.let { "$it MHz" } ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi channel width", wifi.channelWidth))
            add(DiagnosticsFieldUiModel("Wi-Fi RSSI", wifi.rssiDbm?.let { "$it dBm" } ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi link", wifi.linkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi RX link", wifi.rxLinkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi TX link", wifi.txLinkSpeedMbps?.let { "$it Mbps" } ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi hidden SSID", wifi.hiddenSsid?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi Passpoint", wifi.isPasspoint?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi OSU AP", wifi.isOsuAp?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi network ID", wifi.networkId?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Wi-Fi gateway", if (showSensitiveDetails) wifi.gateway ?: "Unknown" else redactValue(wifi.gateway)))
            add(DiagnosticsFieldUiModel("Wi-Fi DHCP server", if (showSensitiveDetails) wifi.dhcpServer ?: "Unknown" else redactValue(wifi.dhcpServer)))
            add(DiagnosticsFieldUiModel("Wi-Fi IP", if (showSensitiveDetails) wifi.ipAddress ?: "Unknown" else redactValue(wifi.ipAddress)))
            add(DiagnosticsFieldUiModel("Wi-Fi subnet", if (showSensitiveDetails) wifi.subnetMask ?: "Unknown" else redactValue(wifi.subnetMask)))
            add(DiagnosticsFieldUiModel("Wi-Fi lease", wifi.leaseDurationSeconds?.let { "${it}s" } ?: "Unknown"))
        }
        snapshot.cellularDetails?.let { cellular ->
            add(DiagnosticsFieldUiModel("Carrier", cellular.carrierName))
            add(DiagnosticsFieldUiModel("SIM operator", cellular.simOperatorName))
            add(DiagnosticsFieldUiModel("Network operator", cellular.networkOperatorName))
            add(DiagnosticsFieldUiModel("Network country", cellular.networkCountryIso))
            add(DiagnosticsFieldUiModel("SIM country", cellular.simCountryIso))
            add(DiagnosticsFieldUiModel("Operator code", cellular.operatorCode))
            add(DiagnosticsFieldUiModel("SIM operator code", cellular.simOperatorCode))
            add(DiagnosticsFieldUiModel("Data network", cellular.dataNetworkType))
            add(DiagnosticsFieldUiModel("Voice network", cellular.voiceNetworkType))
            add(DiagnosticsFieldUiModel("Data state", cellular.dataState))
            add(DiagnosticsFieldUiModel("Service state", cellular.serviceState))
            add(DiagnosticsFieldUiModel("Roaming", cellular.isNetworkRoaming?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Carrier ID", cellular.carrierId?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("SIM carrier ID", cellular.simCarrierId?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Signal level", cellular.signalLevel?.toString() ?: "Unknown"))
            add(DiagnosticsFieldUiModel("Signal dBm", cellular.signalDbm?.let { "$it dBm" } ?: "Unknown"))
        }
    }

private fun DiagnosticsUiFactorySupport.buildHostAutolearnOverviewSummary(
    service: com.poyka.ripdpi.diagnostics.ServiceContextModel,
): String {
    val state = formatAutolearnState(service.hostAutolearnEnabled)
    val details =
        buildList {
            if (service.learnedHostCount > 0) {
                add("${service.learnedHostCount} learned")
            }
            if (service.penalizedHostCount > 0) {
                add("${service.penalizedHostCount} penalized")
            }
        }
    return if (details.isEmpty()) state else "$state · ${details.joinToString(" · ")}"
}

private fun formatAutolearnState(value: String): String =
    when (value.lowercase(Locale.US)) {
        "enabled" -> "Active"
        "disabled" -> "Off"
        else -> "Unknown"
    }

private fun formatAutolearnHost(value: String): String =
    value.takeUnless { it.isBlank() || it == "none" } ?: "None yet"

private fun formatAutolearnGroup(value: String): String =
    value.takeUnless { it.isBlank() || it == "none" }?.let { "Route $it" } ?: "None yet"

private fun formatAutolearnAction(value: String): String =
    when (value.lowercase(Locale.US)) {
        "host_promoted" -> "Promoted best route"
        "group_penalized" -> "Penalized failing route"
        "store_reset" -> "Reset stored hosts"
        "none", "" -> "None yet"
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }
