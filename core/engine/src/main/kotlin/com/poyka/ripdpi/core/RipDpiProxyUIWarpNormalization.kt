package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultWarpLocalSocksPort
import com.poyka.ripdpi.data.DefaultWarpManualEndpointPort
import com.poyka.ripdpi.data.WarpAmneziaPresetOff
import com.poyka.ripdpi.data.normalizeWarpEndpointSelectionMode
import com.poyka.ripdpi.data.normalizeWarpRouteMode

internal fun normalizeWarpConfig(config: RipDpiWarpConfig): RipDpiWarpConfig =
    config.copy(
        routeMode = normalizeWarpRouteMode(config.routeMode),
        routeHosts = config.routeHosts.trim(),
        endpointSelectionMode = normalizeWarpEndpointSelectionMode(config.endpointSelectionMode),
        manualEndpoint =
            config.manualEndpoint.copy(
                host = config.manualEndpoint.host.trim(),
                ipv4 = config.manualEndpoint.ipv4.trim(),
                ipv6 = config.manualEndpoint.ipv6.trim(),
                port =
                    config.manualEndpoint.port.takeIf { it in 1..MaxValidPortNumber }
                        ?: DefaultWarpManualEndpointPort,
            ),
        scannerParallelism = config.scannerParallelism.coerceAtLeast(1),
        scannerMaxRttMs = config.scannerMaxRttMs.coerceAtLeast(1),
        amneziaPreset = config.amneziaPreset.trim().ifBlank { WarpAmneziaPresetOff },
        localSocksHost = config.localSocksHost.ifBlank { "127.0.0.1" },
        localSocksPort = config.localSocksPort.takeIf { it in 1..MaxValidPortNumber } ?: DefaultWarpLocalSocksPort,
    )
