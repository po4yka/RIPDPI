package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.security.PinVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

class SettingsViewModelBootstrapper
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val strategyPackStateStore: StrategyPackStateStore,
        private val pinVerifier: PinVerifier,
    ) {
        fun initialize(
            scope: CoroutineScope,
            stringResolver: com.poyka.ripdpi.platform.StringResolver,
            effects: MutableSharedFlow<SettingsEffect>,
            loadInitialHostPackCatalog: () -> Unit,
            loadInitialStrategyPackCatalog: () -> Unit,
            updateStrategyPackCatalogRuntimeState: (StrategyPackRuntimeState) -> Unit,
        ) {
            loadInitialHostPackCatalog()
            loadInitialStrategyPackCatalog()
            scope.launch {
                strategyPackStateStore.state.collect { runtimeState ->
                    updateStrategyPackCatalogRuntimeState(runtimeState)
                }
            }
            scope.launch {
                ensurePinKeyIntegrity(stringResolver, effects)
            }
        }

        private suspend fun ensurePinKeyIntegrity(
            stringResolver: com.poyka.ripdpi.platform.StringResolver,
            effects: MutableSharedFlow<SettingsEffect>,
        ) {
            val settings = appSettingsRepository.snapshot()
            if (settings.backupPin.isNotBlank() && !pinVerifier.isKeyAvailable()) {
                appSettingsRepository.update {
                    setBackupPin("")
                    setBiometricEnabled(false)
                }
                effects.emit(
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_pin_key_lost_title),
                        message = stringResolver.getString(R.string.notice_pin_key_lost_message),
                        tone = SettingsNoticeTone.Warning,
                    ),
                )
            }
        }
    }
