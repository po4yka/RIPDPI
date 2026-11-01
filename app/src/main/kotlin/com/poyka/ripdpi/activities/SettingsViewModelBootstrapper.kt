package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.StrategyPackRuntimeState
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.security.PinVerifier
import kotlinx.coroutines.CoroutineScope
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
                ensurePinKeyIntegrity()
            }
        }

        private suspend fun ensurePinKeyIntegrity() {
            val settings = appSettingsRepository.snapshot()
            // Wipe only on a definite "no key": an unreadable keystore must not disable the app lock.
            if (settings.backupPin.isNotBlank() && pinVerifier.isKeyAvailable() == false) {
                appSettingsRepository.update {
                    setBackupPin("")
                    setBiometricEnabled(false)
                    setAppLockResetNoticePending(true)
                }
            }
        }
    }
