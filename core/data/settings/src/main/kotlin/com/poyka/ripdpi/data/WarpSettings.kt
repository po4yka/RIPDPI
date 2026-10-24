package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.wireguard.AmneziaWgWireProfile
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

// TODO(cloudflare-removal): All hosts below are Cloudflare-only (cloudflareclient.com).
// WARP provisioning, scanning, and Zero Trust enrollment all require reachability to this domain.
// Plan: mirror registration behind own infra or demote WARP for Russian users before CF removal.
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
    val wireProfile: String = AmneziaWgWireProfile.ID,
    val i1: String = "",
    val i2: String = "",
    val i3: String = "",
    val i4: String = "",
    val i5: String = "",
    val enabled: Boolean = false,
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val h1: String = "1",
    val h2: String = "2",
    val h3: String = "3",
    val h4: String = "4",
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
        "balanced" -> WarpAmneziaPresetQuicImitation
        "aggressive" -> WarpAmneziaPresetTlsImitation
        WarpAmneziaPresetRandom -> WarpAmneziaPresetRandom
        WarpAmneziaPresetQuicImitation -> WarpAmneziaPresetQuicImitation
        WarpAmneziaPresetTlsImitation -> WarpAmneziaPresetTlsImitation
        WarpAmneziaPresetDnsImitation -> WarpAmneziaPresetDnsImitation
        WarpAmneziaPresetCustom -> WarpAmneziaPresetCustom
        else -> WarpAmneziaPresetOff
    }

private val builtInWarpPresetProfiles: Map<String, WarpAmneziaSettings> by lazy {
    builtInWarpPayloadGenPresets().associate { definition ->
        definition.id to definition.toSettings()
    }
}

internal fun rawWarpAmneziaSettings(appSettings: AppSettings): WarpAmneziaSettings =
    WarpAmneziaSettings(
        wireProfile =
            appSettings.warpAmneziaWireProfile.ifEmpty {
                if (appSettings.warpAmneziaEnabled ||
                    appSettings.warpAmneziaPreset !in setOf("", WarpAmneziaPresetOff)
                ) {
                    ""
                } else {
                    AmneziaWgWireProfile.ID
                }
            },
        i1 = appSettings.warpAmneziaI1,
        i2 = appSettings.warpAmneziaI2,
        i3 = appSettings.warpAmneziaI3,
        i4 = appSettings.warpAmneziaI4,
        i5 = appSettings.warpAmneziaI5,
        enabled = appSettings.warpAmneziaEnabled,
        jc = appSettings.warpAmneziaJc,
        jmin = appSettings.warpAmneziaJmin,
        jmax = appSettings.warpAmneziaJmax,
        h1 = appSettings.warpAmneziaH1.ifEmpty { "1" },
        h2 = appSettings.warpAmneziaH2.ifEmpty { "2" },
        h3 = appSettings.warpAmneziaH3.ifEmpty { "3" },
        h4 = appSettings.warpAmneziaH4.ifEmpty { "4" },
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
    val inferredPreset =
        builtInWarpPresetProfiles.entries
            .firstOrNull { (_, settings) ->
                settings == rawSettings
            }?.key
    return when {
        normalizedPreset != WarpAmneziaPresetOff || storedPreset.isNotBlank() -> normalizedPreset
        rawSettings == WarpAmneziaSettings() -> WarpAmneziaPresetOff
        inferredPreset != null -> inferredPreset
        else -> WarpAmneziaPresetCustom
    }
}

fun resolveWarpAmneziaProfile(
    preset: String,
    rawSettings: WarpAmneziaSettings,
): WarpAmneziaPresetProfile {
    val normalizedPreset = normalizeWarpAmneziaPreset(preset)
    return when (normalizedPreset) {
        WarpAmneziaPresetRandom,
        WarpAmneziaPresetQuicImitation,
        WarpAmneziaPresetTlsImitation,
        WarpAmneziaPresetDnsImitation,
        -> {
            WarpAmneziaPresetProfile(
                normalizedPreset,
                builtInWarpPresetProfiles[normalizedPreset] ?: WarpAmneziaSettings(),
            )
        }

        WarpAmneziaPresetCustom -> {
            WarpAmneziaPresetProfile(normalizedPreset, rawSettings)
        }

        else -> {
            WarpAmneziaPresetProfile(WarpAmneziaPresetOff, WarpAmneziaSettings())
        }
    }
}

fun WarpAmneziaSettings.requireSupportedProfile() {
    AmneziaWgWireProfile.requireValid(wireProfile, listOf(h1, h2, h3, h4), listOf(i1, i2, i3, i4, i5))
    AmneziaWgWireProfile.requireScalars(jc, jmin, jmax, s1, s2)
    require(s3 == 0 && s4 == 0) { "AmneziaWG S3/S4 must remain zero" }
}

fun AppSettings.toWarpSettingsModel(): WarpSettingsModel =
    rawWarpAmneziaSettings(this).let { rawAmnezia ->
        val amneziaProfile =
            if (rawAmnezia.wireProfile !=
                AmneziaWgWireProfile.ID
            ) {
                WarpAmneziaPresetProfile(WarpAmneziaPresetCustom, rawAmnezia)
            } else {
                resolveWarpAmneziaProfile(
                    inferWarpAmneziaPreset(warpAmneziaPreset, rawAmnezia),
                    rawAmnezia,
                )
            }
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
