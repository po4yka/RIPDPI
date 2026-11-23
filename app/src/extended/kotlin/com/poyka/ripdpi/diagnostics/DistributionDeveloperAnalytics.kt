package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.proto.AppSettings

internal fun distributionDeveloperConfigDiff(
    settings: AppSettings,
    defaults: AppSettings,
): DeveloperConfigDiffEntry? =
    DeveloperConfigDiffEntry(
        key = "rootModeEnabled",
        defaultValue = defaults.rootModeAuthorized.toString(),
        actualValue = settings.rootModeAuthorized.toString(),
    ).takeIf { settings.rootModeAuthorized != defaults.rootModeAuthorized }
