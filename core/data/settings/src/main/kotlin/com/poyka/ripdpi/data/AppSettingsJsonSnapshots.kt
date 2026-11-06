package com.poyka.ripdpi.data

import kotlinx.serialization.Serializable

internal const val AppSettingsJsonFormatVersion = 1

internal val defaultSettings = AppSettingsSerializer.defaultValue

@Serializable
internal data class AppSettingsSnapshot(
    val formatVersion: Int = AppSettingsJsonFormatVersion,
    val rootUiRuntime: AppSettingsRootUiRuntimeSnapshot = AppSettingsRootUiRuntimeSnapshot(),
    val dns: AppSettingsDnsSnapshot = AppSettingsDnsSnapshot(),
    val proxyDesync: AppSettingsProxyDesyncSnapshot = AppSettingsProxyDesyncSnapshot(),
    val chains: AppSettingsChainSnapshot = AppSettingsChainSnapshot(),
    val quicAdaptive: AppSettingsQuicAdaptiveSnapshot = AppSettingsQuicAdaptiveSnapshot(),
    val warp: AppSettingsWarpSnapshot = AppSettingsWarpSnapshot(),
    val relay: AppSettingsRelaySnapshot = AppSettingsRelaySnapshot(),
    val routing: AppSettingsRoutingSnapshot = AppSettingsRoutingSnapshot(),
)
