package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.canonicalDefaultDnsProviderDefinition
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingDnsSystemId
import com.poyka.ripdpi.ui.screens.xray.XrayNativeProviderSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

private val DefaultOnboardingDnsProviderId = canonicalDefaultDnsProviderDefinition().providerId
private val DefaultOnboardingDnsSettings = AppSettingsSerializer.defaultValue

class OnboardingSettingsCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val xrayNativeProviderSelection: XrayNativeProviderSelection,
    ) {
        fun observeSelections(
            scope: CoroutineScope,
            onSelectionChanged: (Mode, String) -> Unit,
        ) {
            scope.launch {
                appSettingsRepository.settings.collect { settings ->
                    if (settings.onboardingComplete) return@collect
                    onSelectionChanged(
                        Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue }),
                        settings.onboardingDnsProviderId(),
                    )
                }
            }
        }

        suspend fun saveSelection(
            mode: Mode,
            dnsProviderId: String,
            persona: String,
        ) {
            if (mode == Mode.Proxy) {
                xrayNativeProviderSelection.selectNativeMode(mode)
            }
            appSettingsRepository.update {
                setRipdpiMode(mode.preferenceValue)
                applyOnboardingDnsProvider(dnsProviderId)
                setUiPersona(normalizePersona(persona))
            }
        }

        suspend fun complete(state: OnboardingUiState) {
            if (state.selectedMode == Mode.Proxy) {
                xrayNativeProviderSelection.selectNativeMode(state.selectedMode)
            }
            appSettingsRepository.update {
                setOnboardingComplete(true)
                setRipdpiMode(state.selectedMode.preferenceValue)
                applyOnboardingDnsProvider(state.selectedDnsProviderId)
                setUiPersona(normalizePersona(state.selectedPersona))
            }
        }
    }

private fun AppSettings.Builder.applyOnboardingDnsProvider(dnsProviderId: String) {
    if (dnsProviderId == OnboardingDnsSystemId) {
        setDnsProviderId(DefaultOnboardingDnsSettings.dnsProviderId)
        setDnsMode(DefaultOnboardingDnsSettings.dnsMode)
        setDnsIp(DefaultOnboardingDnsSettings.dnsIp)
        setEncryptedDnsProtocol(DefaultOnboardingDnsSettings.encryptedDnsProtocol)
        setEncryptedDnsHost(DefaultOnboardingDnsSettings.encryptedDnsHost)
        setEncryptedDnsPort(DefaultOnboardingDnsSettings.encryptedDnsPort)
        setEncryptedDnsTlsServerName(DefaultOnboardingDnsSettings.encryptedDnsTlsServerName)
        clearEncryptedDnsBootstrapIps()
        addAllEncryptedDnsBootstrapIps(DefaultOnboardingDnsSettings.encryptedDnsBootstrapIpsList)
        setEncryptedDnsDohUrl(DefaultOnboardingDnsSettings.encryptedDnsDohUrl)
    } else {
        setDnsProviderId(dnsProviderId)
    }
}

private fun AppSettings.onboardingDnsProviderId(): String =
    when {
        dnsProviderId.isBlank() -> OnboardingDnsSystemId
        hasUntouchedDefaultDnsSettings() -> OnboardingDnsSystemId
        else -> dnsProviderId.ifEmpty { DefaultOnboardingDnsProviderId }
    }

private fun AppSettings.hasUntouchedDefaultDnsSettings(): Boolean =
    dnsProviderId == DefaultOnboardingDnsProviderId &&
        dnsMode == DefaultOnboardingDnsSettings.dnsMode &&
        dnsIp == DefaultOnboardingDnsSettings.dnsIp &&
        encryptedDnsProtocol == DefaultOnboardingDnsSettings.encryptedDnsProtocol &&
        encryptedDnsHost == DefaultOnboardingDnsSettings.encryptedDnsHost &&
        encryptedDnsPort == DefaultOnboardingDnsSettings.encryptedDnsPort &&
        encryptedDnsTlsServerName == DefaultOnboardingDnsSettings.encryptedDnsTlsServerName &&
        encryptedDnsBootstrapIpsList == DefaultOnboardingDnsSettings.encryptedDnsBootstrapIpsList &&
        encryptedDnsDohUrl == DefaultOnboardingDnsSettings.encryptedDnsDohUrl

private fun normalizePersona(persona: String): String = if (persona == "advanced") "advanced" else "simple"
