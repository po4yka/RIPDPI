package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingConnectionTestRunner
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private val DefaultOnboardingPageCount = OnboardingPages.size

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Running : ConnectionTestState

    data class Success(
        val latencyMs: Long,
    ) : ConnectionTestState

    data class Failed(
        val reason: String,
    ) : ConnectionTestState
}

data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = DefaultOnboardingPageCount,
    val selectedMode: Mode = Mode.VPN,
    val selectedDnsProviderId: String = DnsProviderCloudflare,
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
)

sealed interface OnboardingEffect {
    data object OnboardingComplete : OnboardingEffect
}

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val connectionTestRunner: OnboardingConnectionTestRunner,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
        val effects: Flow<OnboardingEffect> = _effects.receiveAsFlow()

        fun setCurrentPage(page: Int) {
            _uiState.update { state ->
                state.copy(currentPage = page.coerceIn(0, state.totalPages - 1))
            }
        }

        fun nextPage() {
            _uiState.update { state ->
                state.copy(currentPage = (state.currentPage + 1).coerceAtMost(state.totalPages - 1))
            }
        }

        fun previousPage() {
            _uiState.update { state ->
                state.copy(currentPage = (state.currentPage - 1).coerceAtLeast(0))
            }
        }

        fun selectMode(mode: Mode) {
            _uiState.update { it.copy(selectedMode = mode) }
        }

        fun selectDnsProvider(providerId: String) {
            _uiState.update { it.copy(selectedDnsProviderId = providerId) }
        }

        fun runConnectionTest() {
            _uiState.update { it.copy(connectionTestState = ConnectionTestState.Running) }
            viewModelScope.launch {
                val result = connectionTestRunner.runTest()
                _uiState.update { it.copy(connectionTestState = result) }
            }
        }

        fun skip() {
            finish()
        }

        fun finish() {
            val state = _uiState.value
            viewModelScope.launch {
                appSettingsRepository.update {
                    setOnboardingComplete(true)
                    setRipdpiMode(state.selectedMode.preferenceValue)
                    setDnsProviderId(state.selectedDnsProviderId)
                }
                _effects.send(OnboardingEffect.OnboardingComplete)
            }
        }
    }
