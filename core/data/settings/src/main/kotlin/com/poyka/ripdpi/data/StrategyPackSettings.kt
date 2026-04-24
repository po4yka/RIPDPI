package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

data class StrategyPackSettingsModel(
    val channel: String = DefaultStrategyPackChannel,
    val pinnedPackId: String = DefaultStrategyPackPinnedId,
    val pinnedPackVersion: String = DefaultStrategyPackPinnedVersion,
    val refreshPolicy: String = DefaultStrategyPackRefreshPolicy,
    val allowRollbackOverride: Boolean = false,
)

fun AppSettings.toStrategyPackSettingsModel(): StrategyPackSettingsModel =
    StrategyPackSettingsModel(
        channel = normalizeStrategyPackChannel(strategyPackChannel),
        pinnedPackId = strategyPackPinnedId,
        pinnedPackVersion = strategyPackPinnedVersion,
        refreshPolicy = normalizeStrategyPackRefreshPolicy(strategyPackRefreshPolicy),
        allowRollbackOverride = strategyPackAllowRollbackOverride,
    )
