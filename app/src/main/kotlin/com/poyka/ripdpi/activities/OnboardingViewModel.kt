package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
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

private const val DefaultOnboardingPageCount = 3

data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = DefaultOnboardingPageCount,
)

sealed interface OnboardingEffect {
    data object OnboardingComplete : OnboardingEffect
}

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
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

        fun skip() {
            finish()
        }

        fun finish() {
            viewModelScope.launch {
                appSettingsRepository.update {
                    setOnboardingComplete(true)
                }
                _effects.send(OnboardingEffect.OnboardingComplete)
            }
        }
    }
