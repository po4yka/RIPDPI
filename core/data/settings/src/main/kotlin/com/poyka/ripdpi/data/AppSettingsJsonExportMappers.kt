package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

internal fun AppSettings.toSnapshot(): AppSettingsSnapshot =
    AppSettingsSnapshot(
        rootUiRuntime =
            AppSettingsRootUiRuntimeSnapshot(
                appTheme = appTheme,
                mode = Mode.fromString(ripdpiMode.ifEmpty { AppSettingsSerializer.defaultValue.ripdpiMode }),
            ),
    ).withDnsSnapshot(this)
        .withProxyDesyncSnapshot(this)
        .withQuicAdaptiveSnapshot(this)
        .withWarpSnapshot(this)
        .withRelaySnapshot(toSettingsSections().relay)
        .withRoutingSnapshot(this)
        .withChainSnapshot(this)
        .withUiRuntimeSnapshot(this)
