package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.security.PinVerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class SettingsViewModel
    @Suppress("LongParameterList")
    @Inject
    internal constructor(
        private val settingsUiDependencies: SettingsUiDependencies,
        private val settingsActionDependencies: SettingsActionDependencies,
        private val settingsViewModelBootstrapper: SettingsViewModelBootstrapper,
        settingsUiStateAssembler: SettingsUiStateAssembler,
    ) : ViewModel() {
        private val _effects =
            MutableSharedFlow<SettingsEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private val hostAutolearnStoreRefresh = MutableStateFlow(0)
        private val strategyPackCatalogState = MutableStateFlow(StrategyPackCatalogUiState())
        private val mutations =
            SettingsMutationRunner(
                viewModelScope,
                settingsUiDependencies.appSettingsRepository,
                _effects,
            )

        val effects: SharedFlow<SettingsEffect> = _effects.asSharedFlow()
        val hostPackCatalog: StateFlow<HostPackCatalogUiState> =
            settingsActionDependencies.hostPackCatalogUiStateStore.state
        val strategyPackCatalog: StateFlow<StrategyPackCatalogUiState> = strategyPackCatalogState

        val uiState: StateFlow<SettingsUiState> =
            settingsUiStateAssembler.assemble(
                scope = viewModelScope,
                settingsUiDependencies = settingsUiDependencies,
                hostAutolearnStoreRefresh = hostAutolearnStoreRefresh,
            )

        private val dnsActions = SettingsDnsActions(mutations)
        private val customizationActions by lazy {
            SettingsCustomizationActions(
                mutations = mutations,
                launcherIconController = settingsActionDependencies.launcherIconController,
                currentUiState = { uiState.value },
                pinVerifier = settingsActionDependencies.pinVerifier,
                pinLockoutManager = settingsActionDependencies.pinLockoutManager,
            )
        }
        private val maintenanceActions by lazy {
            SettingsMaintenanceActions(
                stringResolver = settingsActionDependencies.stringResolver,
                hostAutolearnStoreController = settingsActionDependencies.hostAutolearnStoreController,
                rememberedPolicySource = settingsActionDependencies.rememberedPolicySource,
                strategyPackService = settingsActionDependencies.strategyPackService,
                strategyPackStateStore = settingsActionDependencies.strategyPackStateStore,
                hostPackCatalogUiStateCoordinator = settingsActionDependencies.hostPackCatalogUiStateCoordinator,
                hostPackCatalogUiStateStore = settingsActionDependencies.hostPackCatalogUiStateStore,
                telemetrySaltStore = settingsActionDependencies.telemetrySaltStore,
                mutations = mutations,
                hostAutolearnStoreRefresh = hostAutolearnStoreRefresh,
                strategyPackCatalogState = strategyPackCatalogState,
                currentUiState = { uiState.value },
                currentServiceStatus = { settingsActionDependencies.serviceStateStore.status.value.first },
            )
        }

        init {
            settingsViewModelBootstrapper.initialize(
                scope = viewModelScope,
                stringResolver = settingsActionDependencies.stringResolver,
                effects = _effects,
                loadInitialHostPackCatalog = maintenanceActions::loadInitialHostPackCatalog,
                loadInitialStrategyPackCatalog = maintenanceActions::loadInitialStrategyPackCatalog,
                updateStrategyPackCatalogRuntimeState = { runtimeState ->
                    strategyPackCatalogState.value =
                        strategyPackCatalogState.value.copy(
                            runtimeState = runtimeState,
                            isRefreshing = false,
                        )
                },
            )
        }

        fun update(transform: SettingsMutation) {
            mutations.update(transform)
        }

        fun updateSetting(
            key: String,
            value: String,
            transform: SettingsMutation,
        ) {
            mutations.updateSetting(key = key, value = value, transform = transform)
        }

        fun selectBuiltInDnsProvider(providerId: String) = dnsActions.selectBuiltInDnsProvider(providerId)

        fun setEncryptedDnsProtocol(protocol: String) = dnsActions.setEncryptedDnsProtocol(protocol)

        fun setPlainDnsServer(dnsIp: String) = dnsActions.setPlainDnsServer(dnsIp)

        fun setCustomDohResolver(
            dohUrl: String,
            bootstrapIps: List<String>,
        ) = dnsActions.setCustomDohResolver(dohUrl = dohUrl, bootstrapIps = bootstrapIps)

        fun setCustomDotResolver(
            host: String,
            port: Int,
            tlsServerName: String,
            bootstrapIps: List<String>,
        ) = dnsActions.setCustomDotResolver(
            host = host,
            port = port,
            tlsServerName = tlsServerName,
            bootstrapIps = bootstrapIps,
        )

        fun setCustomDnsCryptResolver(
            host: String,
            port: Int,
            providerName: String,
            publicKey: String,
            bootstrapIps: List<String>,
        ) = dnsActions.setCustomDnsCryptResolver(
            host = host,
            port = port,
            providerName = providerName,
            publicKey = publicKey,
            bootstrapIps = bootstrapIps,
        )

        fun setWebRtcProtectionEnabled(enabled: Boolean) = customizationActions.setWebRtcProtectionEnabled(enabled)

        fun setExcludeRussianAppsEnabled(enabled: Boolean) = customizationActions.setExcludeRussianAppsEnabled(enabled)

        fun setFullTunnelMode(enabled: Boolean) = customizationActions.setFullTunnelMode(enabled)

        fun setAppTheme(theme: String) = customizationActions.setAppTheme(theme)

        fun setAppIcon(iconKey: String) = customizationActions.setAppIcon(iconKey)

        fun setThemedAppIconEnabled(enabled: Boolean) = customizationActions.setThemedAppIconEnabled(enabled)

        fun setBiometricEnabled(enabled: Boolean) = customizationActions.setBiometricEnabled(enabled)

        fun setBackupPin(pin: String) = customizationActions.setBackupPin(pin)

        fun verifyBackupPin(pin: String): PinVerifyResult = customizationActions.verifyBackupPin(pin)

        fun isPinLockedOut(): Boolean = settingsActionDependencies.pinLockoutManager.isLockedOut()

        fun pinLockoutRemainingMs(): Long = settingsActionDependencies.pinLockoutManager.remainingLockoutMs()

        fun resetSettings() = maintenanceActions.resetSettings()

        fun applyHostPackPreset(
            preset: HostPackPreset,
            targetMode: String,
            applyMode: String,
        ) = maintenanceActions.applyHostPackPreset(
            preset = preset,
            targetMode = targetMode,
            applyMode = applyMode,
        )

        fun refreshHostPackCatalog() = maintenanceActions.refreshHostPackCatalog()

        fun forgetLearnedHosts() = maintenanceActions.forgetLearnedHosts()

        fun clearRememberedNetworks() = maintenanceActions.clearRememberedNetworks()

        fun refreshStrategyPackCatalog() = maintenanceActions.refreshStrategyPackCatalog()

        fun resetFakeTlsProfile() = maintenanceActions.resetFakeTlsProfile()

        fun resetAdaptiveFakeTtlProfile() = maintenanceActions.resetAdaptiveFakeTtlProfile()

        fun resetFakePayloadLibrary() = maintenanceActions.resetFakePayloadLibrary()

        fun resetHttpParserEvasions() = maintenanceActions.resetHttpParserEvasions()

        fun resetActivationWindow() = maintenanceActions.resetActivationWindow()

        fun rotateTelemetrySalt() = maintenanceActions.rotateTelemetrySalt()
    }
