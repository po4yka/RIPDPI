package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

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
        .setSimpleFailoverAwgProfileId(rootUiRuntime.simpleFailoverAwgProfileId)
        .build()
}
