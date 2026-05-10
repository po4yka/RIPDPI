package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withWarpSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
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
        warpManualEndpointPort = settings.warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort,
        warpScannerEnabled = settings.warpScannerEnabled,
        warpScannerParallelism = settings.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism,
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
        warpAmneziaPreset = inferWarpAmneziaPreset(settings.warpAmneziaPreset, rawWarpAmneziaSettings(settings)),
    )

@Suppress("LongMethod")
internal fun AppSettings.Builder.applyWarpSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setWarpEnabled(snapshot.warpEnabled)
        .setWarpRouteMode(normalizeWarpRouteMode(snapshot.warpRouteMode))
        .setWarpRouteHosts(snapshot.warpRouteHosts)
        .setWarpBuiltinRulesEnabled(snapshot.warpBuiltinRulesEnabled)
        .setWarpProfileId(snapshot.warpProfileId.ifBlank { DefaultWarpProfileId })
        .setWarpAccountKind(normalizeWarpAccountKind(snapshot.warpAccountKind))
        .setWarpZeroTrustOrg(snapshot.warpZeroTrustOrg)
        .setWarpSetupState(normalizeWarpSetupState(snapshot.warpSetupState))
        .setWarpLastScannerMode(normalizeWarpScannerMode(snapshot.warpLastScannerMode))
        .setWarpEndpointSelectionMode(normalizeWarpEndpointSelectionMode(snapshot.warpEndpointSelectionMode))
        .setWarpManualEndpointHost(snapshot.warpManualEndpointHost)
        .setWarpManualEndpointV4(snapshot.warpManualEndpointV4)
        .setWarpManualEndpointV6(snapshot.warpManualEndpointV6)
        .setWarpManualEndpointPort(snapshot.warpManualEndpointPort.takeIf { it > 0 } ?: DefaultWarpManualEndpointPort)
        .setWarpScannerEnabled(snapshot.warpScannerEnabled)
        .setWarpScannerParallelism(snapshot.warpScannerParallelism.takeIf { it > 0 } ?: DefaultWarpScannerParallelism)
        .setWarpScannerMaxRttMs(snapshot.warpScannerMaxRttMs.takeIf { it > 0 } ?: DefaultWarpScannerMaxRttMs)
        .setWarpAmneziaEnabled(snapshot.warpAmneziaEnabled)
        .setWarpAmneziaJc(snapshot.warpAmneziaJc)
        .setWarpAmneziaJmin(snapshot.warpAmneziaJmin)
        .setWarpAmneziaJmax(snapshot.warpAmneziaJmax)
        .setWarpAmneziaH1(snapshot.warpAmneziaH1)
        .setWarpAmneziaH2(snapshot.warpAmneziaH2)
        .setWarpAmneziaH3(snapshot.warpAmneziaH3)
        .setWarpAmneziaH4(snapshot.warpAmneziaH4)
        .setWarpAmneziaS1(snapshot.warpAmneziaS1)
        .setWarpAmneziaS2(snapshot.warpAmneziaS2)
        .setWarpAmneziaS3(snapshot.warpAmneziaS3)
        .setWarpAmneziaS4(snapshot.warpAmneziaS4)
        .setWarpAmneziaPreset(
            inferWarpAmneziaPreset(
                snapshot.warpAmneziaPreset,
                WarpAmneziaSettings(
                    enabled = snapshot.warpAmneziaEnabled,
                    jc = snapshot.warpAmneziaJc,
                    jmin = snapshot.warpAmneziaJmin,
                    jmax = snapshot.warpAmneziaJmax,
                    h1 = snapshot.warpAmneziaH1,
                    h2 = snapshot.warpAmneziaH2,
                    h3 = snapshot.warpAmneziaH3,
                    h4 = snapshot.warpAmneziaH4,
                    s1 = snapshot.warpAmneziaS1,
                    s2 = snapshot.warpAmneziaS2,
                    s3 = snapshot.warpAmneziaS3,
                    s4 = snapshot.warpAmneziaS4,
                ),
            ),
        )
