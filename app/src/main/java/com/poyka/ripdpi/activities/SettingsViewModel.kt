package com.poyka.ripdpi.activities

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.core.hasHostAutolearnStore
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.HostPackPreset
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import com.poyka.ripdpi.hosts.HostPackCatalogRepository
import com.poyka.ripdpi.platform.LauncherIconController
import com.poyka.ripdpi.services.ServiceStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val appContext: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val hostPackCatalogRepository: HostPackCatalogRepository,
        private val launcherIconController: LauncherIconController,
        private val serviceStateStore: ServiceStateStore,
        private val telemetrySaltStore: com.poyka.ripdpi.services.TelemetryInstallSaltStore,
    ) : ViewModel() {
    private val _effects = Channel<SettingsEffect>(Channel.BUFFERED)
    private val hostAutolearnStoreRefresh = MutableStateFlow(0)
    private val hostPackCatalogState = MutableStateFlow(HostPackCatalogUiState())
    private val mutations = SettingsMutationRunner(viewModelScope, appSettingsRepository, _effects)

    val effects: Flow<SettingsEffect> = _effects.receiveAsFlow()
    val hostPackCatalog: StateFlow<HostPackCatalogUiState> = hostPackCatalogState

    val uiState: StateFlow<SettingsUiState> =
        combine(
            appSettingsRepository.settings,
            serviceStateStore.telemetry,
            hostAutolearnStoreRefresh,
            rememberedNetworkPolicyStore.observePolicies(limit = 64),
        ) { settings, telemetry, _, rememberedPolicies ->
            settings.toUiState(
                serviceStatus = telemetry.status,
                proxyTelemetry = telemetry.proxyTelemetry,
                hostAutolearnStorePresent = hasHostAutolearnStore(appContext),
                rememberedNetworkCount = rememberedPolicies.size,
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue =
                    AppSettingsSerializer.defaultValue.toUiState(
                        isHydrated = false,
                        serviceStatus = serviceStateStore.telemetry.value.status,
                        proxyTelemetry = serviceStateStore.telemetry.value.proxyTelemetry,
                        hostAutolearnStorePresent = hasHostAutolearnStore(appContext),
                        rememberedNetworkCount = 0,
                    ),
            )

    private val dnsActions = SettingsDnsActions(mutations)
    private val customizationActions by lazy {
        SettingsCustomizationActions(
            mutations = mutations,
            launcherIconController = launcherIconController,
            currentUiState = { uiState.value },
        )
    }
    private val maintenanceActions by lazy {
        SettingsMaintenanceActions(
            appContext = appContext,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            hostPackCatalogRepository = hostPackCatalogRepository,
            mutations = mutations,
            hostAutolearnStoreRefresh = hostAutolearnStoreRefresh,
            hostPackCatalogState = hostPackCatalogState,
            currentUiState = { uiState.value },
            currentServiceStatus = { serviceStateStore.status.value.first },
        )
    }

    init {
        maintenanceActions.loadInitialHostPackCatalog()
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

    fun setAppTheme(theme: String) = customizationActions.setAppTheme(theme)

    fun setAppIcon(iconKey: String) = customizationActions.setAppIcon(iconKey)

    fun setThemedAppIconEnabled(enabled: Boolean) = customizationActions.setThemedAppIconEnabled(enabled)

    fun setBiometricEnabled(enabled: Boolean) = customizationActions.setBiometricEnabled(enabled)

    fun setBackupPin(pin: String) = customizationActions.setBackupPin(pin)

    fun verifyBackupPin(pin: String): Boolean = customizationActions.verifyBackupPin(pin)

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
                    title = "Telemetry salt reset",
                    message = "A new salt will be generated on the next connection. " +
                        "Previously recorded fingerprint hashes can no longer be correlated.",
                    tone = SettingsNoticeTone.Info,
                ),
            )
        }
    }
}
