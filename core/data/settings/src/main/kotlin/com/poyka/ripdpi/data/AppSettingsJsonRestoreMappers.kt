package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

private const val AppSettingsJsonFormatVersion = 1

internal fun AppSettingsSnapshot.toAppSettings(): AppSettings {
    require(formatVersion == AppSettingsJsonFormatVersion) {
        "Unsupported app settings format version: $formatVersion"
    }

    val activeDns = toActiveDnsSettings()
    return AppSettings
        .newBuilder()
        .applyRootUiRuntimeSnapshot(this)
        .applyDnsSnapshot(activeDns)
        .applyProxyDesyncSnapshot(this)
        .applyQuicAdaptiveSnapshot(this)
        .applyWarpSnapshot(this)
        .applyRelaySnapshot(this)
        .applyRoutingSnapshot(this)
        .applyChainSnapshots(this)
        .build()
}
