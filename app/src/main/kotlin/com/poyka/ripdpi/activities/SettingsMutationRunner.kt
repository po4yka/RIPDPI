package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
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

    suspend fun updateSettingAndAwait(
        key: String,
        value: String,
        canApply: AppSettings.Builder.() -> Boolean = { true },
        transform: SettingsMutation,
    ): Boolean {
        var applied = false
        appSettingsRepository.update {
            applied = canApply()
            if (applied) transform()
        }
        if (applied) effects.emit(SettingsEffect.SettingChanged(key = key, value = value))
        return applied
    }

    fun launch(block: suspend SettingsMutationRunner.() -> Unit) {
        scope.launch { guarded { block() } }
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
            guarded {
                appSettingsRepository.update(transform)
                effect?.let { effects.emit(it) }
            }
        }
    }

    // A storage failure (full disk, EIO) must not reach the uncaught handler and kill the app and its
    // VPN service. The settings flow keeps showing the stored value. The log names only the class:
    // the message can carry stored values.
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            Logger.w { "Settings action failed: ${ignored.javaClass.simpleName}" }
        }
    }
}
