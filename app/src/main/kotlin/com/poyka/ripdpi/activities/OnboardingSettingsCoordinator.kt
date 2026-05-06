package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

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
                        settings.dnsProviderId.ifEmpty { DnsProviderCloudflare },
                    )
                }
            }
        }

        suspend fun saveSelection(
            mode: Mode,
            dnsProviderId: String,
        ) {
            appSettingsRepository.update {
                setRipdpiMode(mode.preferenceValue)
                setDnsProviderId(dnsProviderId)
            }
        }

        suspend fun complete(state: OnboardingUiState) {
            appSettingsRepository.update {
                setOnboardingComplete(true)
                setRipdpiMode(state.selectedMode.preferenceValue)
                setDnsProviderId(state.selectedDnsProviderId)
            }
        }
    }
