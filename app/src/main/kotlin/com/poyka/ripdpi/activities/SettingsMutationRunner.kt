package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal class SettingsMutationRunner(
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val effects: MutableSharedFlow<SettingsEffect>,
) {
    fun update(transform: SettingsMutation) {
        mutate(effect = null, transform = transform)
    }

    fun updateSetting(
        key: String,
        value: String,
        transform: SettingsMutation,
    ) {
        mutate(
            effect = SettingsEffect.SettingChanged(key = key, value = value),
            transform = transform,
        )
    }

    fun launch(block: suspend SettingsMutationRunner.() -> Unit) {
        scope.launch { block() }
    }

    suspend fun updateDirect(transform: SettingsMutation) {
        appSettingsRepository.update(transform)
    }

    suspend fun replace(
        settings: AppSettings,
        effect: SettingsEffect? = null,
    ) {
        appSettingsRepository.replace(settings)
        effect?.let { effects.emit(it) }
    }

    suspend fun emit(effect: SettingsEffect) {
        effects.emit(effect)
    }

    private fun mutate(
        effect: SettingsEffect?,
        transform: SettingsMutation,
    ) {
        scope.launch {
            appSettingsRepository.update(transform)
            effect?.let { effects.emit(it) }
        }
    }
}
