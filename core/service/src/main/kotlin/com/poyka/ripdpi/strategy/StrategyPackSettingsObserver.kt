package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.toStrategyPackSettingsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class StrategyPackSettingsObserver(
    private val appSettingsRepository: AppSettingsRepository,
    private val applicationScope: CoroutineScope,
) {
    fun observeRelevantChanges(
        initialKey: StrategyPackRefreshKey,
        onRelevantChange: suspend (StrategyPackRefreshKey) -> Unit,
    ): Job =
        applicationScope.launch {
            var firstEmission = true
            appSettingsRepository.settings
                .map { settings -> StrategyPackRefreshKey(settings.toStrategyPackSettingsModel()) }
                .distinctUntilChanged()
                .collect { key ->
                    if (firstEmission) {
                        firstEmission = false
                        if (key == initialKey) {
                            return@collect
                        }
                    }
                    onRelevantChange(key)
                }
        }
}
