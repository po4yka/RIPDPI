package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val WarpRouteModeOff = "off"
const val WarpRouteModeRules = "rules"
const val WarpEndpointSelectionAutomatic = "automatic"
const val WarpEndpointSelectionManual = "manual"
const val DefaultWarpProfileId = "default"
const val WarpAccountKindConsumerFree = "consumer_free"
const val WarpAccountKindConsumerPlus = "consumer_plus"
const val WarpAccountKindZeroTrust = "zero_trust"
const val WarpSetupStateNotConfigured = "not_configured"
const val WarpSetupStateProvisioning = "provisioning"
const val WarpSetupStateProvisioned = "provisioned"
const val WarpSetupStateNeedsAttention = "needs_attention"
const val WarpScannerModeAutomatic = "automatic"
const val WarpScannerModeManual = "manual"
const val WarpScannerModeRescan = "rescan"
const val DefaultWarpLocalSocksPort = 11888
const val DefaultWarpScannerParallelism = 10
const val DefaultWarpScannerMaxRttMs = 1_500
const val DefaultWarpManualEndpointPort = 2408

val BuiltInWarpControlPlaneHosts: List<String> =
    listOf(
        "api.cloudflareclient.com",
        "connectivity.cloudflareclient.com",
        "engage.cloudflareclient.com",
        "downloads.cloudflareclient.com",
        "zero-trust-client.cloudflareclient.com",
        "pkg.cloudflareclient.com",
        "consumer-masque.cloudflareclient.com",
    )

data class WarpManualEndpoint(
    val host: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
    val port: Int = DefaultWarpManualEndpointPort,
)

data class WarpAmneziaSettings(
    val enabled: Boolean = false,
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val h1: Long = 0L,
    val h2: Long = 0L,
    val h3: Long = 0L,
    val h4: Long = 0L,
    val s1: Int = 0,
    val s2: Int = 0,
    val s3: Int = 0,
    val s4: Int = 0,
)

data class WarpProfileMetadata(
    val profileId: String = DefaultWarpProfileId,
    val accountKind: String = WarpAccountKindConsumerFree,
    val zeroTrustOrg: String = "",
    val setupState: String = WarpSetupStateNotConfigured,
    val lastScannerMode: String = WarpScannerModeAutomatic,
)

data class WarpSettingsModel(
    val enabled: Boolean = false,
    val routeMode: String = WarpRouteModeOff,
    val routeHosts: String = "",
    val builtInRulesEnabled: Boolean = true,
    val profile: WarpProfileMetadata = WarpProfileMetadata(),
    val endpointSelectionMode: String = WarpEndpointSelectionAutomatic,
    val manualEndpoint: WarpManualEndpoint = WarpManualEndpoint(),
    val scannerEnabled: Boolean = true,
    val scannerParallelism: Int = DefaultWarpScannerParallelism,
    val scannerMaxRttMs: Int = DefaultWarpScannerMaxRttMs,
    val amnezia: WarpAmneziaSettings = WarpAmneziaSettings(),
)

fun normalizeWarpRouteMode(value: String): String =
    when (value.trim().lowercase()) {
        WarpRouteModeRules -> WarpRouteModeRules
        else -> WarpRouteModeOff
    }

fun normalizeWarpEndpointSelectionMode(value: String): String =
    when (value.trim().lowercase()) {
        WarpEndpointSelectionManual -> WarpEndpointSelectionManual
        else -> WarpEndpointSelectionAutomatic
    }

fun normalizeWarpAccountKind(value: String): String =
    when (value.trim().lowercase()) {
        WarpAccountKindConsumerPlus -> WarpAccountKindConsumerPlus
        WarpAccountKindZeroTrust -> WarpAccountKindZeroTrust
        else -> WarpAccountKindConsumerFree
    }

fun normalizeWarpSetupState(value: String): String =
    when (value.trim().lowercase()) {
        WarpSetupStateProvisioning -> WarpSetupStateProvisioning
        WarpSetupStateProvisioned -> WarpSetupStateProvisioned
        WarpSetupStateNeedsAttention -> WarpSetupStateNeedsAttention
        else -> WarpSetupStateNotConfigured
    }

fun normalizeWarpScannerMode(value: String): String =
    when (value.trim().lowercase()) {
        WarpScannerModeManual -> WarpScannerModeManual
        WarpScannerModeRescan -> WarpScannerModeRescan
        else -> WarpScannerModeAutomatic
    }

fun AppSettings.toWarpSettingsModel(): WarpSettingsModel =
    WarpSettingsModel(
        enabled = warpEnabled,
        routeMode = normalizeWarpRouteMode(warpRouteMode),
        routeHosts = warpRouteHosts,
        builtInRulesEnabled = warpBuiltinRulesEnabled,
        profile =
            WarpProfileMetadata(
                profileId = warpProfileId.ifBlank { DefaultWarpProfileId },
                accountKind = normalizeWarpAccountKind(warpAccountKind),
                zeroTrustOrg = warpZeroTrustOrg,
                setupState = normalizeWarpSetupState(warpSetupState),
                lastScannerMode = normalizeWarpScannerMode(warpLastScannerMode),
            ),
        endpointSelectionMode = normalizeWarpEndpointSelectionMode(warpEndpointSelectionMode),
        manualEndpoint =
            WarpManualEndpoint(
                host = warpManualEndpointHost,
                ipv4 = warpManualEndpointV4,
                ipv6 = warpManualEndpointV6,
                port = warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort,
            ),
        scannerEnabled = warpScannerEnabled,
        scannerParallelism = warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism,
        scannerMaxRttMs = warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs,
        amnezia =
            WarpAmneziaSettings(
                enabled = warpAmneziaEnabled,
                jc = warpAmneziaJc,
                jmin = warpAmneziaJmin,
                jmax = warpAmneziaJmax,
                h1 = warpAmneziaH1,
                h2 = warpAmneziaH2,
                h3 = warpAmneziaH3,
                h4 = warpAmneziaH4,
                s1 = warpAmneziaS1,
                s2 = warpAmneziaS2,
                s3 = warpAmneziaS3,
                s4 = warpAmneziaS4,
            ),
    )
