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
const val WarpAmneziaPresetOff = "off"
const val WarpAmneziaPresetBalanced = "balanced"
const val WarpAmneziaPresetAggressive = "aggressive"
const val WarpAmneziaPresetCustom = "custom"

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

data class WarpAmneziaPresetProfile(
    val preset: String,
    val settings: WarpAmneziaSettings,
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
    val amneziaPreset: String = WarpAmneziaPresetOff,
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

fun normalizeWarpAmneziaPreset(value: String): String =
    when (value.trim().lowercase()) {
        WarpAmneziaPresetBalanced -> WarpAmneziaPresetBalanced
        WarpAmneziaPresetAggressive -> WarpAmneziaPresetAggressive
        WarpAmneziaPresetCustom -> WarpAmneziaPresetCustom
        else -> WarpAmneziaPresetOff
    }

private fun balancedWarpAmneziaSettings(): WarpAmneziaSettings =
    WarpAmneziaSettings(
        enabled = true,
        jc = 3,
        jmin = 50,
        jmax = 400,
        h1 = 1L,
        h2 = 3L,
        h3 = 5L,
        h4 = 7L,
        s1 = 32,
        s2 = 120,
        s3 = 260,
        s4 = 520,
    )

private fun aggressiveWarpAmneziaSettings(): WarpAmneziaSettings =
    WarpAmneziaSettings(
        enabled = true,
        jc = 6,
        jmin = 64,
        jmax = 900,
        h1 = 2L,
        h2 = 4L,
        h3 = 6L,
        h4 = 8L,
        s1 = 48,
        s2 = 160,
        s3 = 512,
        s4 = 960,
    )

internal fun rawWarpAmneziaSettings(appSettings: AppSettings): WarpAmneziaSettings =
    WarpAmneziaSettings(
        enabled = appSettings.warpAmneziaEnabled,
        jc = appSettings.warpAmneziaJc,
        jmin = appSettings.warpAmneziaJmin,
        jmax = appSettings.warpAmneziaJmax,
        h1 = appSettings.warpAmneziaH1,
        h2 = appSettings.warpAmneziaH2,
        h3 = appSettings.warpAmneziaH3,
        h4 = appSettings.warpAmneziaH4,
        s1 = appSettings.warpAmneziaS1,
        s2 = appSettings.warpAmneziaS2,
        s3 = appSettings.warpAmneziaS3,
        s4 = appSettings.warpAmneziaS4,
    )

internal fun inferWarpAmneziaPreset(
    storedPreset: String,
    rawSettings: WarpAmneziaSettings,
): String {
    val normalizedPreset = normalizeWarpAmneziaPreset(storedPreset)
    if (normalizedPreset != WarpAmneziaPresetOff || storedPreset.isNotBlank()) {
        return normalizedPreset
    }
    return if (
        rawSettings.enabled ||
        rawSettings.jc != 0 ||
        rawSettings.jmin != 0 ||
        rawSettings.jmax != 0 ||
        rawSettings.h1 != 0L ||
        rawSettings.h2 != 0L ||
        rawSettings.h3 != 0L ||
        rawSettings.h4 != 0L ||
        rawSettings.s1 != 0 ||
        rawSettings.s2 != 0 ||
        rawSettings.s3 != 0 ||
        rawSettings.s4 != 0
    ) {
        WarpAmneziaPresetCustom
    } else {
        WarpAmneziaPresetOff
    }
}

fun resolveWarpAmneziaProfile(
    preset: String,
    rawSettings: WarpAmneziaSettings,
): WarpAmneziaPresetProfile {
    val normalizedPreset = normalizeWarpAmneziaPreset(preset)
    return when (normalizedPreset) {
        WarpAmneziaPresetBalanced -> WarpAmneziaPresetProfile(normalizedPreset, balancedWarpAmneziaSettings())
        WarpAmneziaPresetAggressive -> WarpAmneziaPresetProfile(normalizedPreset, aggressiveWarpAmneziaSettings())
        WarpAmneziaPresetCustom -> WarpAmneziaPresetProfile(normalizedPreset, normalizeWarpAmneziaSettings(rawSettings))
        else -> WarpAmneziaPresetProfile(WarpAmneziaPresetOff, WarpAmneziaSettings())
    }
}

fun normalizeWarpAmneziaSettings(settings: WarpAmneziaSettings): WarpAmneziaSettings {
    val normalizedJmin = settings.jmin.coerceAtLeast(0)
    val normalizedJmax = settings.jmax.coerceAtLeast(normalizedJmin)
    val normalizedHeaders =
        listOf(settings.h1, settings.h2, settings.h3, settings.h4)
            .mapIndexed { index, value ->
                value.takeIf { it > 0L } ?: (index + 1).toLong()
            }.fold(mutableListOf<Long>()) { acc, value ->
                var candidate = value
                while (acc.contains(candidate)) {
                    candidate += 1L
                }
                acc += candidate
                acc
            }
    val normalizedS1 = settings.s1.coerceAtLeast(0)
    var normalizedS2 = settings.s2.coerceAtLeast(0)
    if (normalizedS1 + 56 == normalizedS2) {
        normalizedS2 += 1
    }
    return settings.copy(
        enabled = settings.enabled,
        jc = settings.jc.coerceIn(0, 10),
        jmin = normalizedJmin,
        jmax = normalizedJmax.coerceAtMost(1024),
        h1 = normalizedHeaders[0],
        h2 = normalizedHeaders[1],
        h3 = normalizedHeaders[2],
        h4 = normalizedHeaders[3],
        s1 = normalizedS1,
        s2 = normalizedS2,
        s3 = settings.s3.coerceAtLeast(0),
        s4 = settings.s4.coerceAtLeast(0),
    )
}

fun AppSettings.toWarpSettingsModel(): WarpSettingsModel =
    rawWarpAmneziaSettings(this).let { rawAmnezia ->
        val amneziaProfile =
            resolveWarpAmneziaProfile(
                inferWarpAmneziaPreset(warpAmneziaPreset, rawAmnezia),
                rawAmnezia,
            )
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
            amneziaPreset = amneziaProfile.preset,
            amnezia = amneziaProfile.settings,
        )
    }
