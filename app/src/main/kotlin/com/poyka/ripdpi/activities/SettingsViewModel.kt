package com.poyka.ripdpi.activities

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.WarpPayloadGenCatalog
import com.poyka.ripdpi.diagnostics.DiagnosticsRememberedPolicySource
import com.poyka.ripdpi.hosts.HostPackCatalogRepository
import com.poyka.ripdpi.platform.HostAutolearnStoreController
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.security.BiometricAuthManager
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.security.PinVerifier
import com.poyka.ripdpi.security.PinVerifyResult
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import com.poyka.ripdpi.strategy.StrategyPackService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val rememberedPolicySource: DiagnosticsRememberedPolicySource,
        private val hostPackCatalogRepository: HostPackCatalogRepository,
        private val strategyPackStateStore: StrategyPackStateStore,
        private val launcherIconController: LauncherIconController,
        private val serviceStateStore: ServiceStateStore,
        private val stringResolver: StringResolver,
        private val hostAutolearnStoreController: HostAutolearnStoreController,
        private val telemetrySaltStore: com.poyka.ripdpi.services.TelemetryInstallSaltStore,
        private val routingProtectionCatalogService: RoutingProtectionCatalogService,
        private val warpPayloadGenCatalog: WarpPayloadGenCatalog,
        private val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        private val strategyPackService: StrategyPackService,
        private val pinVerifier: PinVerifier,
        private val pinLockoutManager: PinLockoutManager,
        private val application: Application,
    ) : ViewModel() {
        private val biometricAuthManager = BiometricAuthManager()
        private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
        private val hostAutolearnStoreRefresh = MutableStateFlow(0)
        private val hostPackCatalogState = MutableStateFlow(HostPackCatalogUiState())
        private val strategyPackCatalogState = MutableStateFlow(StrategyPackCatalogUiState())
        private val mutations = SettingsMutationRunner(viewModelScope, appSettingsRepository, _effects)

        val effects: Flow<SettingsEffect> = _effects.receiveAsFlow()
        val hostPackCatalog: StateFlow<HostPackCatalogUiState> = hostPackCatalogState
        val strategyPackCatalog: StateFlow<StrategyPackCatalogUiState> = strategyPackCatalogState

        val uiState: StateFlow<SettingsUiState> =
            combine(
                appSettingsRepository.settings,
                serviceStateStore.telemetry,
                hostAutolearnStoreRefresh,
                rememberedPolicySource.observePolicies(limit = 64),
                flow { emit(routingProtectionCatalogService.snapshot()) },
            ) { settings, telemetry, _, rememberedPolicies, routingProtectionSnapshot ->
                val warpSuggestion =
                    runCatching {
                        warpPayloadGenCatalog.suggestFor(
                            networkSnapshotProvider.capture(),
                        )
                    }.getOrNull()
                settings.toUiState(
                    serviceStatus = telemetry.status,
                    proxyTelemetry = telemetry.proxyTelemetry,
                    serviceTelemetry = telemetry,
                    hostAutolearnStorePresent = hostAutolearnStoreController.hasStore(),
                    rememberedNetworkCount = rememberedPolicies.size,
                    runtimeOverrideRememberedPolicy = rememberedPolicies.isNotEmpty(),
                    biometricAvailability = biometricAuthManager.canAuthenticate(application),
                    routingProtectionSnapshot = routingProtectionSnapshot,
                    suggestedWarpAmneziaPresetId = warpSuggestion?.preset?.id.orEmpty(),
                    suggestedWarpAmneziaPresetLabel = warpSuggestion?.preset?.label.orEmpty(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue =
                    AppSettingsSerializer.defaultValue.toUiState(
                        isHydrated = false,
                        serviceStatus = serviceStateStore.telemetry.value.status,
                        proxyTelemetry = serviceStateStore.telemetry.value.proxyTelemetry,
                        serviceTelemetry = serviceStateStore.telemetry.value,
                        hostAutolearnStorePresent = hostAutolearnStoreController.hasStore(),
                        rememberedNetworkCount = 0,
                        runtimeOverrideRememberedPolicy = false,
                        biometricAvailability = biometricAuthManager.canAuthenticate(application),
                        routingProtectionSnapshot = routingProtectionCatalogService.snapshot(),
                        suggestedWarpAmneziaPresetId = "",
                        suggestedWarpAmneziaPresetLabel = "",
                    ),
            )

        private val dnsActions = SettingsDnsActions(mutations)
        private val customizationActions by lazy {
            SettingsCustomizationActions(
                mutations = mutations,
                launcherIconController = launcherIconController,
                currentUiState = { uiState.value },
                pinVerifier = pinVerifier,
                pinLockoutManager = pinLockoutManager,
            )
        }
        private val maintenanceActions by lazy {
            SettingsMaintenanceActions(
                stringResolver = stringResolver,
                hostAutolearnStoreController = hostAutolearnStoreController,
                rememberedPolicySource = rememberedPolicySource,
                hostPackCatalogRepository = hostPackCatalogRepository,
                strategyPackService = strategyPackService,
                strategyPackStateStore = strategyPackStateStore,
                mutations = mutations,
                hostAutolearnStoreRefresh = hostAutolearnStoreRefresh,
                hostPackCatalogState = hostPackCatalogState,
                strategyPackCatalogState = strategyPackCatalogState,
                currentUiState = { uiState.value },
                currentServiceStatus = { serviceStateStore.status.value.first },
            )
        }

        init {
            maintenanceActions.loadInitialHostPackCatalog()
            maintenanceActions.loadInitialStrategyPackCatalog()
            viewModelScope.launch {
                strategyPackStateStore.state.collect { runtimeState ->
                    strategyPackCatalogState.value =
                        strategyPackCatalogState.value.copy(
                            runtimeState = runtimeState,
                            isRefreshing = false,
                        )
                }
            }
            ensurePinKeyIntegrity()
        }

        private fun ensurePinKeyIntegrity() {
            viewModelScope.launch {
                val settings = appSettingsRepository.snapshot()
                if (settings.backupPin.isNotBlank() && !pinVerifier.isKeyAvailable()) {
                    appSettingsRepository.update {
                        setBackupPin("")
                        setBiometricEnabled(false)
                    }
                    _effects.send(
                        SettingsEffect.Notice(
                            title = stringResolver.getString(R.string.notice_pin_key_lost_title),
                            message = stringResolver.getString(R.string.notice_pin_key_lost_message),
                            tone = SettingsNoticeTone.Warning,
                        ),
                    )
                }
            }
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

        fun isPinLockedOut(): Boolean = pinLockoutManager.isLockedOut()

        fun pinLockoutRemainingMs(): Long = pinLockoutManager.remainingLockoutMs()

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

        fun rotateTelemetrySalt() {
            viewModelScope.launch {
                telemetrySaltStore.rotateSalt()
                _effects.send(
                    SettingsEffect.Notice(
                        title = stringResolver.getString(R.string.notice_telemetry_salt_reset_title),
                        message = stringResolver.getString(R.string.notice_telemetry_salt_reset_message),
                        tone = SettingsNoticeTone.Info,
                    ),
                )
            }
        }
    }
