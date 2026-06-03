package com.poyka.ripdpi.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsCoordinator: OnboardingSettingsCoordinator,
        private val validationCoordinator: OnboardingValidationCoordinator,
        private val permissionCoordinator: OnboardingPermissionCoordinator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OnboardingUiState())
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        private val _effects =
            MutableSharedFlow<OnboardingEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

        private var validationJob: Job? = null

        init {
            observeOnboardingSelections()
        }

        fun setCurrentPage(page: Int) {
            if (_uiState.value.validationState.isBusy) {
                return
            }
            _uiState.update { state ->
                state.copy(currentPage = page.coerceIn(0, state.totalPages - 1))
            }
        }

        fun nextPage() {
            if (_uiState.value.validationState.isBusy) {
                return
            }
            _uiState.update { state ->
                state.copy(currentPage = (state.currentPage + 1).coerceAtMost(state.totalPages - 1))
            }
        }

        fun previousPage() {
            if (_uiState.value.validationState.isBusy) {
                return
            }
            _uiState.update { state ->
                state.copy(currentPage = (state.currentPage - 1).coerceAtLeast(0))
            }
        }

        fun selectMode(mode: Mode) {
            val current = _uiState.value
            if (current.selectedMode == mode) {
                return
            }
            invalidateValidation()
            _uiState.update {
                it.copy(selectedMode = mode).withValidationState(OnboardingValidationState.Idle)
            }
            viewModelScope.launch {
                settingsCoordinator.saveSelection(mode = mode, dnsProviderId = current.selectedDnsProviderId)
            }
        }

        fun selectDnsProvider(providerId: String) {
            val current = _uiState.value
            if (current.selectedDnsProviderId == providerId) {
                return
            }
            invalidateValidation()
            _uiState.update {
                it.copy(selectedDnsProviderId = providerId).withValidationState(OnboardingValidationState.Idle)
            }
            viewModelScope.launch {
                settingsCoordinator.saveSelection(mode = current.selectedMode, dnsProviderId = providerId)
            }
        }

        fun runValidation() {
            if (_uiState.value.validationState.isBusy) {
                return
            }
            validationJob?.cancel()
            validationJob =
                viewModelScope.launch {
                    val currentState = _uiState.value
                    settingsCoordinator.saveSelection(
                        mode = currentState.selectedMode,
                        dnsProviderId = currentState.selectedDnsProviderId,
                    )

                    when (val prompt = permissionCoordinator.nextValidationPrompt(currentState.selectedMode)) {
                        OnboardingPermissionPrompt.Notifications -> {
                            _uiState.update {
                                it.withValidationState(OnboardingValidationState.RequestingNotifications)
                            }
                            _effects.emit(OnboardingEffect.RequestNotificationsPermission)
                            return@launch
                        }

                        is OnboardingPermissionPrompt.VpnConsent -> {
                            _uiState.update {
                                it.withValidationState(OnboardingValidationState.RequestingVpnConsent)
                            }
                            _effects.emit(OnboardingEffect.RequestVpnConsent(prompt.intent))
                            return@launch
                        }

                        null -> {
                        }
                    }
                    performValidation(currentState.selectedMode)
                }
        }

        fun onNotificationPermissionResult(result: PermissionResult) {
            handlePermissionOutcome(permissionCoordinator.onNotificationPermissionResult(result))
        }

        fun onVpnPermissionResult(result: PermissionResult) {
            handlePermissionOutcome(permissionCoordinator.onVpnPermissionResult(result))
        }

        fun acceptSuggestedMode() {
            val suggestedMode =
                (_uiState.value.validationState as? OnboardingValidationState.Failed)?.suggestedMode
                    ?: return
            selectMode(suggestedMode)
        }

        fun finishKeepingRunning() {
            if (_uiState.value.validationState !is OnboardingValidationState.Success) {
                return
            }
            validationCoordinator.retainActiveValidation()
            completeOnboarding()
        }

        fun finishDisconnected() {
            validationCoordinator.stopActiveValidation()
            completeOnboarding()
        }

        fun finishAnyway() {
            validationCoordinator.stopActiveValidation()
            completeOnboarding()
        }

        fun skip() {
            validationCoordinator.stopActiveValidation()
            completeOnboarding()
        }

        override fun onCleared() {
            validationJob?.cancel()
            validationCoordinator.stopActiveValidation()
            super.onCleared()
        }

        private fun observeOnboardingSelections() {
            settingsCoordinator.observeSelections(viewModelScope) { selectedMode, dnsProviderId ->
                _uiState.update { current ->
                    if (current.selectedMode == selectedMode &&
                        current.selectedDnsProviderId == dnsProviderId
                    ) {
                        current
                    } else {
                        current
                            .copy(
                                selectedMode = selectedMode,
                                selectedDnsProviderId = dnsProviderId,
                            ).withValidationState(OnboardingValidationState.Idle)
                    }
                }
            }
        }

        private suspend fun performValidation(mode: Mode) {
            val validationState =
                validationCoordinator.validate(mode = mode) { progress ->
                    _uiState.update { it.withValidationState(progress) }
                }
            _uiState.update { current ->
                current.withValidationState(validationState)
            }
        }

        private fun invalidateValidation() {
            validationJob?.cancel()
            validationJob = null
            permissionCoordinator.clearPendingPermission()
            validationCoordinator.stopActiveValidation()
        }

        private fun handlePermissionOutcome(outcome: OnboardingPermissionOutcome?) {
            when (outcome) {
                OnboardingPermissionOutcome.ContinueValidation -> {
                    validationJob?.cancel()
                    validationJob =
                        viewModelScope.launch {
                            performValidation(_uiState.value.selectedMode)
                        }
                }

                is OnboardingPermissionOutcome.Failed -> {
                    _uiState.update { it.withValidationState(outcome.state) }
                }

                null -> {
                }
            }
        }

        private fun completeOnboarding() {
            validationJob?.cancel()
            validationJob = null
            permissionCoordinator.clearPendingPermission()
            val state = _uiState.value
            viewModelScope.launch {
                settingsCoordinator.complete(state)
                _effects.emit(OnboardingEffect.OnboardingComplete)
            }
        }
    }
