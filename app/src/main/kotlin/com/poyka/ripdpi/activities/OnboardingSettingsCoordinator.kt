package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.canonicalDefaultDnsProviderDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

private val DefaultOnboardingDnsProviderId = canonicalDefaultDnsProviderDefinition().providerId

class OnboardingSettingsCoordinator
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
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
                        settings.dnsProviderId.ifEmpty { DefaultOnboardingDnsProviderId },
                    )
                }
            }
        }

        suspend fun saveSelection(
            mode: Mode,
            dnsProviderId: String,
            persona: String,
        ) {
            appSettingsRepository.update {
                setRipdpiMode(mode.preferenceValue)
                setDnsProviderId(dnsProviderId)
                setUiPersona(normalizePersona(persona))
            }
        }

        suspend fun complete(state: OnboardingUiState) {
            appSettingsRepository.update {
                setOnboardingComplete(true)
                setRipdpiMode(state.selectedMode.preferenceValue)
                setDnsProviderId(state.selectedDnsProviderId)
                setUiPersona(normalizePersona(state.selectedPersona))
            }
        }
    }

private fun normalizePersona(persona: String): String = if (persona == "advanced") "advanced" else "simple"
