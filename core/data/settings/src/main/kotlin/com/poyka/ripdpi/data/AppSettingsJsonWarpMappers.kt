package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withWarpSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        warp =
            AppSettingsWarpSnapshot(
                warpEnabled = settings.warpEnabled,
                warpRouteMode = normalizeWarpRouteMode(settings.warpRouteMode),
                warpRouteHosts = settings.warpRouteHosts,
                warpBuiltinRulesEnabled = settings.warpBuiltinRulesEnabled,
                warpProfileId = settings.warpProfileId.ifBlank { DefaultWarpProfileId },
                warpAccountKind = normalizeWarpAccountKind(settings.warpAccountKind),
                warpZeroTrustOrg = settings.warpZeroTrustOrg,
                warpSetupState = normalizeWarpSetupState(settings.warpSetupState),
                warpLastScannerMode = normalizeWarpScannerMode(settings.warpLastScannerMode),
                warpEndpointSelectionMode = normalizeWarpEndpointSelectionMode(settings.warpEndpointSelectionMode),
                warpManualEndpointHost = settings.warpManualEndpointHost,
                warpManualEndpointV4 = settings.warpManualEndpointV4,
                warpManualEndpointV6 = settings.warpManualEndpointV6,
                warpManualEndpointPort =
                    settings.warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort,
                warpScannerEnabled = settings.warpScannerEnabled,
                warpScannerParallelism =
                    settings.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism,
                warpScannerMaxRttMs = settings.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs,
                warpAmneziaEnabled = settings.warpAmneziaEnabled,
                warpAmneziaJc = settings.warpAmneziaJc,
                warpAmneziaJmin = settings.warpAmneziaJmin,
                warpAmneziaJmax = settings.warpAmneziaJmax,
                warpAmneziaH1 = settings.warpAmneziaH1,
                warpAmneziaH2 = settings.warpAmneziaH2,
                warpAmneziaH3 = settings.warpAmneziaH3,
                warpAmneziaH4 = settings.warpAmneziaH4,
                warpAmneziaS1 = settings.warpAmneziaS1,
                warpAmneziaS2 = settings.warpAmneziaS2,
                warpAmneziaS3 = settings.warpAmneziaS3,
                warpAmneziaS4 = settings.warpAmneziaS4,
                warpAmneziaPreset =
                    inferWarpAmneziaPreset(
                        settings.warpAmneziaPreset,
                        rawWarpAmneziaSettings(settings),
                    ),
            ),
    )

@Suppress("LongMethod")
internal fun AppSettings.Builder.applyWarpSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder {
    val warp = snapshot.warp
    return setWarpEnabled(warp.warpEnabled)
        .setWarpRouteMode(normalizeWarpRouteMode(warp.warpRouteMode))
        .setWarpRouteHosts(warp.warpRouteHosts)
        .setWarpBuiltinRulesEnabled(warp.warpBuiltinRulesEnabled)
        .setWarpProfileId(warp.warpProfileId.ifBlank { DefaultWarpProfileId })
        .setWarpAccountKind(normalizeWarpAccountKind(warp.warpAccountKind))
        .setWarpZeroTrustOrg(warp.warpZeroTrustOrg)
        .setWarpSetupState(normalizeWarpSetupState(warp.warpSetupState))
        .setWarpLastScannerMode(normalizeWarpScannerMode(warp.warpLastScannerMode))
        .setWarpEndpointSelectionMode(normalizeWarpEndpointSelectionMode(warp.warpEndpointSelectionMode))
        .setWarpManualEndpointHost(warp.warpManualEndpointHost)
        .setWarpManualEndpointV4(warp.warpManualEndpointV4)
        .setWarpManualEndpointV6(warp.warpManualEndpointV6)
        .setWarpManualEndpointPort(warp.warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort)
        .setWarpScannerEnabled(warp.warpScannerEnabled)
        .setWarpScannerParallelism(warp.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism)
        .setWarpScannerMaxRttMs(warp.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs)
        .setWarpAmneziaEnabled(warp.warpAmneziaEnabled)
        .setWarpAmneziaJc(warp.warpAmneziaJc)
        .setWarpAmneziaJmin(warp.warpAmneziaJmin)
        .setWarpAmneziaJmax(warp.warpAmneziaJmax)
        .setWarpAmneziaH1(warp.warpAmneziaH1)
        .setWarpAmneziaH2(warp.warpAmneziaH2)
        .setWarpAmneziaH3(warp.warpAmneziaH3)
        .setWarpAmneziaH4(warp.warpAmneziaH4)
        .setWarpAmneziaS1(warp.warpAmneziaS1)
        .setWarpAmneziaS2(warp.warpAmneziaS2)
        .setWarpAmneziaS3(warp.warpAmneziaS3)
        .setWarpAmneziaS4(warp.warpAmneziaS4)
        .setWarpAmneziaPreset(
            inferWarpAmneziaPreset(
                warp.warpAmneziaPreset,
                WarpAmneziaSettings(
                    enabled = warp.warpAmneziaEnabled,
                    jc = warp.warpAmneziaJc,
                    jmin = warp.warpAmneziaJmin,
                    jmax = warp.warpAmneziaJmax,
                    h1 = warp.warpAmneziaH1,
                    h2 = warp.warpAmneziaH2,
                    h3 = warp.warpAmneziaH3,
                    h4 = warp.warpAmneziaH4,
                    s1 = warp.warpAmneziaS1,
                    s2 = warp.warpAmneziaS2,
                    s3 = warp.warpAmneziaS3,
                    s4 = warp.warpAmneziaS4,
                ),
            ),
        )
}
