package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

/** Applies the snapshot validators while retaining fields carried only in a FULL protobuf backup. */
fun AppSettings.validatedForBackupRestore(): AppSettings = toSnapshot().toAppSettings(toBuilder())

internal fun AppSettingsSnapshot.toAppSettings(builder: AppSettings.Builder = AppSettings.newBuilder()): AppSettings {
    require(formatVersion == AppSettingsJsonFormatVersion) {
        "Unsupported app settings format version: $formatVersion"
    }

    val activeDns = toActiveDnsSettings()
    return builder
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
