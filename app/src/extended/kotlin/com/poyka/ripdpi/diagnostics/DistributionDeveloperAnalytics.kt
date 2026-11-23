package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.proto.AppSettings

internal fun distributionDeveloperConfigDiff(
    settings: AppSettings,
    defaults: AppSettings,
): DeveloperConfigDiffEntry? =
    DeveloperConfigDiffEntry(
        key = "rootModeEnabled",
        defaultValue = defaults.rootModeEnabled.toString(),
        actualValue = settings.rootModeEnabled.toString(),
    ).takeIf { settings.rootModeEnabled != defaults.rootModeEnabled }
