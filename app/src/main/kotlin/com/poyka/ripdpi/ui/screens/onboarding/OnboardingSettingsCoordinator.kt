package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.canonicalDefaultDnsProviderDefinition
import com.poyka.ripdpi.data.dnsProviderById
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.seed.PresetStatus
import com.poyka.ripdpi.ui.screens.xray.XrayNativeProviderSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Onboarding-only sentinel id for "use the system DNS". It is not a BuiltInDnsProviders id: it means
 * that no built-in resolver is pinned.
 */
internal const val OnboardingDnsSystemId: String = "system"

private val DefaultOnboardingDnsProviderId = canonicalDefaultDnsProviderDefinition().providerId
private val DefaultOnboardingDnsSettings = AppSettingsSerializer.defaultValue

class OnboardingSettingsCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val xrayNativeProviderSelection: XrayNativeProviderSelection,
        private val presetStatus: PresetStatus,
    ) {
        val presetApplied: Boolean get() = presetStatus.applied()

        fun observeSelections(
            scope: CoroutineScope,
            onSelectionChanged: (Mode, String, String) -> Unit,
        ) {
            scope.launch {
                appSettingsRepository.settings.collect { settings ->
                    if (settings.onboardingComplete) return@collect
                    onSelectionChanged(
                        Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue }),
                        settings.onboardingDnsProviderId(),
                        normalizePersona(settings.uiPersona),
                    )
                }
            }
        }

        suspend fun saveSelection(
            mode: Mode,
            dnsProviderId: String,
            persona: String,
        ) {
            persist(mode, dnsProviderId, persona)
        }

        suspend fun complete(state: OnboardingUiState) {
            if (presetApplied) {
                // The state can still hold its defaults here. A mode from the test step is already saved.
                appSettingsRepository.update { setOnboardingComplete(true) }
                return
            }
            persist(state.selectedMode, state.selectedDnsProviderId, state.selectedPersona) {
                setOnboardingComplete(true)
            }
        }

        private suspend fun persist(
            mode: Mode,
            dnsProviderId: String,
            persona: String,
            extra: AppSettings.Builder.() -> Unit = {},
        ) {
            // After a preset no step offers a choice. Only a mode that the test step changed is written, so
            // the preset keeps its provider, its DNS settings, and its interface.
            val keepPreset = presetApplied
            val storedMode = appSettingsRepository.snapshot().ripdpiMode.ifEmpty { Mode.VPN.preferenceValue }
            if (mode == Mode.Proxy && !(keepPreset && storedMode == mode.preferenceValue)) {
                xrayNativeProviderSelection.selectNativeMode(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit,
                    mode,
                )
            }
            appSettingsRepository.update {
                extra()
                setRipdpiMode(mode.preferenceValue)
                if (!keepPreset) {
                    applyOnboardingDnsProvider(dnsProviderId)
                    setUiPersona(normalizePersona(persona))
                }
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
        // The same fields as SettingsDnsActions.selectBuiltInDnsProvider: the id alone does not select a resolver.
        dnsProviderById(dnsProviderId)?.let { resolver ->
            setDnsMode(DnsModeEncrypted)
            setDnsIp(resolver.primaryIp)
            setEncryptedDnsProtocol(resolver.protocol)
            setEncryptedDnsHost(resolver.host)
            setEncryptedDnsPort(resolver.port)
            setEncryptedDnsTlsServerName(resolver.tlsServerName)
            clearEncryptedDnsBootstrapIps()
            addAllEncryptedDnsBootstrapIps(resolver.bootstrapIps)
            setEncryptedDnsDohUrl(resolver.dohUrl.orEmpty())
            setEncryptedDnsDnscryptProviderName(resolver.dnscryptProviderName.orEmpty())
            setEncryptedDnsDnscryptPublicKey(resolver.dnscryptPublicKey.orEmpty())
        }
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
