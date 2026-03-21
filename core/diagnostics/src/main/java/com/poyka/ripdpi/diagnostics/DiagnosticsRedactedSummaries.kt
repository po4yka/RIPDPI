package com.poyka.ripdpi.diagnostics

import kotlinx.serialization.Serializable

@Serializable
internal data class RedactedNetworkSummary(
    val transport: String,
    val dnsServers: String,
    val privateDnsMode: String,
    val publicIp: String,
    val publicAsn: String,
    val localAddresses: String,
    val networkValidated: Boolean,
    val captivePortalDetected: Boolean,
    val wifiDetails: RedactedWifiSummary? = null,
    val cellularDetails: RedactedCellularSummary? = null,
)

@Serializable
internal data class RedactedWifiSummary(
    val ssid: String,
    val bssid: String,
    val band: String,
    val wifiStandard: String,
    val frequencyMhz: Int?,
    val linkSpeedMbps: Int?,
    val rssiDbm: Int?,
    val gateway: String,
)

@Serializable
internal data class RedactedCellularSummary(
    val carrierName: String,
    val networkOperatorName: String,
    val dataNetworkType: String,
    val voiceNetworkType: String,
    val networkCountryIso: String,
    val isNetworkRoaming: Boolean?,
    val signalLevel: Int?,
    val signalDbm: Int?,
)

@Serializable
internal data class RedactedServiceContextSummary(
    val serviceStatus: String,
    val activeMode: String,
    val selectedProfileName: String,
    val configSource: String,
    val proxyEndpoint: String,
    val desyncMethod: String,
    val chainSummary: String,
    val routeGroup: String,
    val restartCount: Int,
    val lastNativeErrorHeadline: String,
)

@Serializable
internal data class RedactedPermissionContextSummary(
    val vpnPermissionState: String,
    val notificationPermissionState: String,
    val batteryOptimizationState: String,
    val dataSaverState: String,
)

@Serializable
internal data class RedactedDeviceContextSummary(
    val appVersionName: String,
    val buildType: String,
    val deviceName: String,
    val androidVersion: String,
    val locale: String,
    val timezone: String,
)

@Serializable
internal data class RedactedEnvironmentContextSummary(
    val batterySaverState: String,
    val powerSaveModeState: String,
    val networkMeteredState: String,
    val roamingState: String,
)

@Serializable
internal data class RedactedDiagnosticContextSummary(
    val service: RedactedServiceContextSummary,
    val permissions: RedactedPermissionContextSummary,
    val device: RedactedDeviceContextSummary,
    val environment: RedactedEnvironmentContextSummary,
)

internal fun NetworkSnapshotModel.toRedactedSummary(): RedactedNetworkSummary =
    RedactedNetworkSummary(
        transport = transport,
        dnsServers = if (dnsServers.isEmpty()) "unknown" else "redacted(${dnsServers.size})",
        privateDnsMode = privateDnsMode,
        publicIp = publicIp?.let { "redacted" } ?: "unknown",
        publicAsn = publicAsn?.let { "redacted" } ?: "unknown",
        localAddresses = if (localAddresses.isEmpty()) "unknown" else "redacted(${localAddresses.size})",
        networkValidated = networkValidated,
        captivePortalDetected = captivePortalDetected,
        wifiDetails =
            wifiDetails?.let {
                RedactedWifiSummary(
                    ssid = if (it.ssid == "unknown") "unknown" else "redacted",
                    bssid = if (it.bssid == "unknown") "unknown" else "redacted",
                    band = it.band,
                    wifiStandard = it.wifiStandard,
                    frequencyMhz = it.frequencyMhz,
                    linkSpeedMbps = it.linkSpeedMbps,
                    rssiDbm = it.rssiDbm,
                    gateway = if (it.gateway.isNullOrBlank()) "unknown" else "redacted",
                )
            },
        cellularDetails =
            cellularDetails?.let {
                RedactedCellularSummary(
                    carrierName = it.carrierName,
                    networkOperatorName = it.networkOperatorName,
                    dataNetworkType = it.dataNetworkType,
                    voiceNetworkType = it.voiceNetworkType,
                    networkCountryIso = it.networkCountryIso,
                    isNetworkRoaming = it.isNetworkRoaming,
                    signalLevel = it.signalLevel,
                    signalDbm = it.signalDbm,
                )
            },
    )

internal fun DiagnosticContextModel.toRedactedSummary(): RedactedDiagnosticContextSummary =
    RedactedDiagnosticContextSummary(
        service =
            RedactedServiceContextSummary(
                serviceStatus = service.serviceStatus,
                activeMode = service.activeMode,
                selectedProfileName = service.selectedProfileName,
                configSource = service.configSource,
                proxyEndpoint = if (service.proxyEndpoint == "unknown") "unknown" else "redacted",
                desyncMethod = service.desyncMethod,
                chainSummary = service.chainSummary,
                routeGroup = service.routeGroup,
                restartCount = service.restartCount,
                lastNativeErrorHeadline = service.lastNativeErrorHeadline,
            ),
        permissions =
            RedactedPermissionContextSummary(
                vpnPermissionState = permissions.vpnPermissionState,
                notificationPermissionState = permissions.notificationPermissionState,
                batteryOptimizationState = permissions.batteryOptimizationState,
                dataSaverState = permissions.dataSaverState,
            ),
        device =
            RedactedDeviceContextSummary(
                appVersionName = device.appVersionName,
                buildType = device.buildType,
                deviceName = "${device.manufacturer} ${device.model}",
                androidVersion = "${device.androidVersion} (API ${device.apiLevel})",
                locale = device.locale,
                timezone = device.timezone,
            ),
        environment =
            RedactedEnvironmentContextSummary(
                batterySaverState = environment.batterySaverState,
                powerSaveModeState = environment.powerSaveModeState,
                networkMeteredState = environment.networkMeteredState,
                roamingState = environment.roamingState,
            ),
    )
