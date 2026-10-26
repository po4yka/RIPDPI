package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.StrategyPackSettingsModel
import com.poyka.ripdpi.data.StrategyPackSnapshot

internal class StrategyPackRefreshExecutor(
    private val repository: StrategyPackRepository,
) {
    suspend fun refresh(
        settings: StrategyPackSettingsModel,
        isCurrent: suspend () -> Boolean,
    ): StrategyPackSnapshot =
        repository.refreshSnapshot(
            channel = settings.channel,
            allowRollbackOverride = settings.allowRollbackOverride,
            isCurrent = isCurrent,
        )

    suspend fun refresh(
        key: StrategyPackRefreshKey,
        isCurrent: suspend () -> Boolean,
    ): StrategyPackSnapshot =
        repository.refreshSnapshot(
            channel = key.channel,
            allowRollbackOverride = key.allowRollbackOverride,
            isCurrent = isCurrent,
        )
}
