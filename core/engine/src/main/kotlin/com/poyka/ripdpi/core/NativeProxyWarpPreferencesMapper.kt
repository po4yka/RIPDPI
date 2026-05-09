package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultWarpLocalSocksPort
import com.poyka.ripdpi.data.toWarpSettingsModel
import com.poyka.ripdpi.proto.AppSettings

internal fun buildWarpConfig(settings: AppSettings): RipDpiWarpConfig {
    val warp = settings.toWarpSettingsModel()
    return RipDpiWarpConfig(
        enabled = warp.enabled,
        routeMode = warp.routeMode,
        routeHosts = warp.routeHosts,
        builtInRulesEnabled = warp.builtInRulesEnabled,
        endpointSelectionMode = warp.endpointSelectionMode,
        manualEndpoint =
            RipDpiWarpManualEndpointConfig(
                host = warp.manualEndpoint.host,
                ipv4 = warp.manualEndpoint.ipv4,
                ipv6 = warp.manualEndpoint.ipv6,
                port = warp.manualEndpoint.port,
            ),
        scannerEnabled = warp.scannerEnabled,
        scannerParallelism = warp.scannerParallelism,
        scannerMaxRttMs = warp.scannerMaxRttMs,
        amneziaPreset = warp.amneziaPreset,
        amnezia =
            RipDpiWarpAmneziaConfig(
                enabled = warp.amnezia.enabled,
                jc = warp.amnezia.jc,
                jmin = warp.amnezia.jmin,
                jmax = warp.amnezia.jmax,
                h1 = warp.amnezia.h1,
                h2 = warp.amnezia.h2,
                h3 = warp.amnezia.h3,
                h4 = warp.amnezia.h4,
                s1 = warp.amnezia.s1,
                s2 = warp.amnezia.s2,
                s3 = warp.amnezia.s3,
                s4 = warp.amnezia.s4,
            ),
        localSocksPort = DefaultWarpLocalSocksPort,
    )
}
