package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsWarpSnapshot(
    val warpEnabled: Boolean = defaultSettings.warpEnabled,
    val warpRouteMode: String = defaultSettings.warpRouteMode,
    val warpRouteHosts: String = defaultSettings.warpRouteHosts,
    val warpBuiltinRulesEnabled: Boolean = defaultSettings.warpBuiltinRulesEnabled,
    val warpProfileId: String = defaultSettings.warpProfileId,
    val warpAccountKind: String = defaultSettings.warpAccountKind,
    val warpZeroTrustOrg: String = defaultSettings.warpZeroTrustOrg,
    val warpSetupState: String = defaultSettings.warpSetupState,
    val warpLastScannerMode: String = defaultSettings.warpLastScannerMode,
    val warpEndpointSelectionMode: String = defaultSettings.warpEndpointSelectionMode,
    val warpManualEndpointHost: String = defaultSettings.warpManualEndpointHost,
    val warpManualEndpointV4: String = defaultSettings.warpManualEndpointV4,
    val warpManualEndpointV6: String = defaultSettings.warpManualEndpointV6,
    val warpManualEndpointPort: Int = defaultSettings.warpManualEndpointPort,
    val warpScannerEnabled: Boolean = defaultSettings.warpScannerEnabled,
    val warpScannerParallelism: Int = defaultSettings.warpScannerParallelism,
    val warpScannerMaxRttMs: Int = defaultSettings.warpScannerMaxRttMs,
    val warpAmneziaEnabled: Boolean = defaultSettings.warpAmneziaEnabled,
    val warpAmneziaJc: Int = defaultSettings.warpAmneziaJc,
    val warpAmneziaJmin: Int = defaultSettings.warpAmneziaJmin,
    val warpAmneziaJmax: Int = defaultSettings.warpAmneziaJmax,
    val warpAmneziaH1: Long = defaultSettings.warpAmneziaH1,
    val warpAmneziaH2: Long = defaultSettings.warpAmneziaH2,
    val warpAmneziaH3: Long = defaultSettings.warpAmneziaH3,
    val warpAmneziaH4: Long = defaultSettings.warpAmneziaH4,
    val warpAmneziaS1: Int = defaultSettings.warpAmneziaS1,
    val warpAmneziaS2: Int = defaultSettings.warpAmneziaS2,
    val warpAmneziaS3: Int = defaultSettings.warpAmneziaS3,
    val warpAmneziaS4: Int = defaultSettings.warpAmneziaS4,
    val warpAmneziaPreset: String = defaultSettings.warpAmneziaPreset,
)

internal fun JsonObjectBuilder.writeWarpSnapshot(snapshot: AppSettingsWarpSnapshot) {
    put("warpEnabled", snapshot.warpEnabled)
    put("warpRouteMode", snapshot.warpRouteMode)
    put("warpRouteHosts", snapshot.warpRouteHosts)
    put("warpBuiltinRulesEnabled", snapshot.warpBuiltinRulesEnabled)
    put("warpProfileId", snapshot.warpProfileId)
    put("warpAccountKind", snapshot.warpAccountKind)
    put("warpZeroTrustOrg", snapshot.warpZeroTrustOrg)
    put("warpSetupState", snapshot.warpSetupState)
    put("warpLastScannerMode", snapshot.warpLastScannerMode)
    put("warpEndpointSelectionMode", snapshot.warpEndpointSelectionMode)
    put("warpManualEndpointHost", snapshot.warpManualEndpointHost)
    put("warpManualEndpointV4", snapshot.warpManualEndpointV4)
    put("warpManualEndpointV6", snapshot.warpManualEndpointV6)
    put("warpManualEndpointPort", snapshot.warpManualEndpointPort)
    put("warpScannerEnabled", snapshot.warpScannerEnabled)
    put("warpScannerParallelism", snapshot.warpScannerParallelism)
    put("warpScannerMaxRttMs", snapshot.warpScannerMaxRttMs)
    put("warpAmneziaEnabled", snapshot.warpAmneziaEnabled)
    put("warpAmneziaJc", snapshot.warpAmneziaJc)
    put("warpAmneziaJmin", snapshot.warpAmneziaJmin)
    put("warpAmneziaJmax", snapshot.warpAmneziaJmax)
    put("warpAmneziaH1", snapshot.warpAmneziaH1)
    put("warpAmneziaH2", snapshot.warpAmneziaH2)
    put("warpAmneziaH3", snapshot.warpAmneziaH3)
    put("warpAmneziaH4", snapshot.warpAmneziaH4)
    put("warpAmneziaS1", snapshot.warpAmneziaS1)
    put("warpAmneziaS2", snapshot.warpAmneziaS2)
    put("warpAmneziaS3", snapshot.warpAmneziaS3)
    put("warpAmneziaS4", snapshot.warpAmneziaS4)
    put("warpAmneziaPreset", snapshot.warpAmneziaPreset)
}

internal fun JsonObject.readWarpSnapshot(defaults: AppSettingsWarpSnapshot): AppSettingsWarpSnapshot =
    AppSettingsWarpSnapshot(
        warpEnabled = booleanValue("warpEnabled", defaults.warpEnabled),
        warpRouteMode = stringValue("warpRouteMode", defaults.warpRouteMode),
        warpRouteHosts = stringValue("warpRouteHosts", defaults.warpRouteHosts),
        warpBuiltinRulesEnabled = booleanValue("warpBuiltinRulesEnabled", defaults.warpBuiltinRulesEnabled),
        warpProfileId = stringValue("warpProfileId", defaults.warpProfileId),
        warpAccountKind = stringValue("warpAccountKind", defaults.warpAccountKind),
        warpZeroTrustOrg = stringValue("warpZeroTrustOrg", defaults.warpZeroTrustOrg),
        warpSetupState = stringValue("warpSetupState", defaults.warpSetupState),
        warpLastScannerMode = stringValue("warpLastScannerMode", defaults.warpLastScannerMode),
        warpEndpointSelectionMode = stringValue("warpEndpointSelectionMode", defaults.warpEndpointSelectionMode),
        warpManualEndpointHost = stringValue("warpManualEndpointHost", defaults.warpManualEndpointHost),
        warpManualEndpointV4 = stringValue("warpManualEndpointV4", defaults.warpManualEndpointV4),
        warpManualEndpointV6 = stringValue("warpManualEndpointV6", defaults.warpManualEndpointV6),
        warpManualEndpointPort = intValue("warpManualEndpointPort", defaults.warpManualEndpointPort),
        warpScannerEnabled = booleanValue("warpScannerEnabled", defaults.warpScannerEnabled),
        warpScannerParallelism = intValue("warpScannerParallelism", defaults.warpScannerParallelism),
        warpScannerMaxRttMs = intValue("warpScannerMaxRttMs", defaults.warpScannerMaxRttMs),
        warpAmneziaEnabled = booleanValue("warpAmneziaEnabled", defaults.warpAmneziaEnabled),
        warpAmneziaJc = intValue("warpAmneziaJc", defaults.warpAmneziaJc),
        warpAmneziaJmin = intValue("warpAmneziaJmin", defaults.warpAmneziaJmin),
        warpAmneziaJmax = intValue("warpAmneziaJmax", defaults.warpAmneziaJmax),
        warpAmneziaH1 = longValue("warpAmneziaH1", defaults.warpAmneziaH1),
        warpAmneziaH2 = longValue("warpAmneziaH2", defaults.warpAmneziaH2),
        warpAmneziaH3 = longValue("warpAmneziaH3", defaults.warpAmneziaH3),
        warpAmneziaH4 = longValue("warpAmneziaH4", defaults.warpAmneziaH4),
        warpAmneziaS1 = intValue("warpAmneziaS1", defaults.warpAmneziaS1),
        warpAmneziaS2 = intValue("warpAmneziaS2", defaults.warpAmneziaS2),
        warpAmneziaS3 = intValue("warpAmneziaS3", defaults.warpAmneziaS3),
        warpAmneziaS4 = intValue("warpAmneziaS4", defaults.warpAmneziaS4),
        warpAmneziaPreset = stringValue("warpAmneziaPreset", defaults.warpAmneziaPreset),
    )
