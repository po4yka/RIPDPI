package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.StrategyPackRefreshPolicyAutomatic
import com.poyka.ripdpi.data.StrategyPackSettingsModel
import com.poyka.ripdpi.data.normalizeStrategyPackRefreshPolicy

internal data class StrategyPackRefreshKey(
    val channel: String,
    val refreshPolicy: String,
    val pinnedPackId: String,
    val pinnedPackVersion: String,
    val allowRollbackOverride: Boolean,
) {
    constructor(settings: StrategyPackSettingsModel) : this(
        channel = settings.channel,
        refreshPolicy = normalizeStrategyPackRefreshPolicy(settings.refreshPolicy),
        pinnedPackId = settings.pinnedPackId,
        pinnedPackVersion = settings.pinnedPackVersion,
        allowRollbackOverride = settings.allowRollbackOverride,
    )

    val isAutomatic: Boolean
        get() = refreshPolicy == StrategyPackRefreshPolicyAutomatic
}
