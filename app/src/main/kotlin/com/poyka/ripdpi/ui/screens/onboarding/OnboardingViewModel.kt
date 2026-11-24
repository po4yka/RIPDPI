package com.poyka.ripdpi.ui.screens.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        // The step survives process death. The selections return from the settings, and the test starts idle.
        private val _uiState =
            MutableStateFlow(
                OnboardingUiState(
                    currentPage = (savedStateHandle.get<Int>(SavedPageKey) ?: 0).coerceIn(0, OnboardingPages.lastIndex),
                    presetApplied = settingsCoordinator.presetApplied,
                    completionReturnPage = savedStateHandle.get<Int>(SavedReturnPageKey),
                    notificationsNoteVisible = permissionCoordinator.notificationsPromptExpected(),
                ),
            )
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        private val _effects =
            MutableSharedFlow<OnboardingEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val effects = _effects.bufferForUiLifecycle(viewModelScope)

        private var validationJob: Job? = null

        // Guards the one-shot, rejection-driven VPN consent request (see [requestVpnConsentIfRejected])
        // so a post-grant re-rejection falls through to the failure state instead of looping the dialog.
        private var vpnConsentRetryUsed = false

        init {
            observeOnboardingSelections()
        }

        /** Every exit shows the completion step first, so it is a step like the others. */
        fun setCurrentPage(page: Int) {
            if (_uiState.value.validationState.isBusy) {
                return
            }
            _uiState.update { state ->
                val target = page.coerceIn(0, state.totalPages - 1)
                if (target == state.currentPage) {
                    state
                } else {
                    state.copy(
                        currentPage = target,
                        completionReturnPage = state.currentPage.takeIf { target == OnboardingCompletionPageIndex },
                        // The user can change the permission in the system settings between two steps.
                        notificationsNoteVisible = permissionCoordinator.notificationsPromptExpected(),
                    )
                }
            }
            val state = _uiState.value
            savedStateHandle[SavedPageKey] = state.currentPage
            savedStateHandle[SavedReturnPageKey] = state.completionReturnPage
        }

        /** Back: from the completion step to the step that the user left, otherwise one step back. */
        fun previousPage() {
            val state = _uiState.value
            // After a passed test the tunnel runs: going back would leave it up under a mode choice.
            if (state.validationState is OnboardingValidationState.Success) {
                return
            }
            setCurrentPage(state.completionReturnPage ?: (state.currentPage - 1))
        }

        fun selectDnsProvider(dnsProviderId: String) {
            val current = _uiState.value
            if (current.selectedDnsProviderId == dnsProviderId) {
                return
            }
            invalidateValidation()
            _uiState.update {
                it.copy(selectedDnsProviderId = dnsProviderId).withValidationState(OnboardingValidationState.Idle)
            }
            viewModelScope.launch {
                settingsCoordinator.saveSelection(
                    mode = current.selectedMode,
                    dnsProviderId = dnsProviderId,
                    persona = current.selectedPersona,
                )
            }
        }

        /** Opens the system VPN settings or the battery optimization list. It requests no permission. */
        fun openSystemSettings(vpn: Boolean) {
            val intent =
                if (vpn) permissionCoordinator.vpnSettingsIntent() else permissionCoordinator.batterySettingsIntent()
            _effects.tryEmit(OnboardingEffect.OpenSystemSettings(intent))
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
                settingsCoordinator.saveSelection(
                    mode = mode,
                    dnsProviderId = current.selectedDnsProviderId,
                    persona = current.selectedPersona,
                )
            }
        }

        fun selectPersona(persona: String) {
            val current = _uiState.value
            val normalized = if (persona == "advanced") "advanced" else "simple"
            if (current.selectedPersona == normalized) {
                return
            }
            _uiState.update { it.copy(selectedPersona = normalized) }
            viewModelScope.launch {
                settingsCoordinator.saveSelection(
                    mode = current.selectedMode,
                    dnsProviderId = current.selectedDnsProviderId,
                    persona = normalized,
                )
            }
        }

        fun runValidation() {
            if (_uiState.value.validationState.isBusy) {
                return
            }
            validationJob?.cancel()
            validationJob =
                viewModelScope.launch {
                    // A fresh, user-initiated attempt re-arms the one-shot consent-on-rejection recovery.
                    vpnConsentRetryUsed = false
                    val currentState = _uiState.value
                    settingsCoordinator.saveSelection(
                        mode = currentState.selectedMode,
                        dnsProviderId = currentState.selectedDnsProviderId,
                        persona = currentState.selectedPersona,
                    )

                    validateWithPermissions(currentState.selectedMode)
                }
        }

        private suspend fun validateWithPermissions(mode: Mode) {
            when (val prompt = permissionCoordinator.nextValidationPrompt(mode)) {
                OnboardingPermissionPrompt.Notifications -> {
                    _uiState.update { it.withValidationState(OnboardingValidationState.RequestingNotifications) }
                    _effects.emit(OnboardingEffect.RequestNotificationsPermission)
                }

                is OnboardingPermissionPrompt.VpnConsent -> {
                    _uiState.update { it.withValidationState(OnboardingValidationState.RequestingVpnConsent) }
                    _effects.emit(OnboardingEffect.RequestVpnConsent(prompt.intent))
                }

                null -> {
                    performValidation(mode)
                }
            }
        }

        fun onNotificationPermissionResult() {
            if (!permissionCoordinator.completeNotificationRequest()) return
            _uiState.update { it.copy(notificationsNoteVisible = false) }
            validationJob?.cancel()
            validationJob = viewModelScope.launch { validateWithPermissions(_uiState.value.selectedMode) }
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

        /** Stops the validated runtime, whoever started it, and leaves onboarding. */
        fun finishDisconnected() {
            viewModelScope.launch {
                validationCoordinator.disconnect()
                completeOnboarding()
            }
        }

        /** Stops any validation runtime and leaves onboarding. */
        fun finish() {
            validationCoordinator.stopActiveValidation()
            completeOnboarding()
        }

        override fun onCleared() {
            validationJob?.cancel()
            validationCoordinator.stopActiveValidation()
            super.onCleared()
        }

        private fun observeOnboardingSelections() {
            settingsCoordinator.observeSelections(viewModelScope) { selectedMode, dnsProviderId, persona ->
                _uiState.update { current ->
                    // The persona does not change what the test checks, so it returns without a test reset.
                    val restored = current.copy(selectedPersona = persona)
                    if (current.selectedMode == selectedMode &&
                        current.selectedDnsProviderId == dnsProviderId
                    ) {
                        restored
                    } else {
                        restored
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
            // The service is the authoritative consent gate: it can reject a VPN start for missing
            // consent even when the advisory pre-check (OnboardingPermissionCoordinator.nextValidationPrompt)
            // reported consent granted, because VpnService.prepare() can disagree across calls. When that
            // happens, launch the system consent dialog off the rejection instead of dead-ending on the
            // "Grant VPN permission" failure state. One-shot per attempt (guarded by vpnConsentRetryUsed)
            // so a re-rejection after the user grants falls through to the failure state rather than looping.
            val failed = validationState as? OnboardingValidationState.Failed
            val rejectedForConsent =
                mode == Mode.VPN &&
                    !vpnConsentRetryUsed &&
                    failed?.recoveryKind == OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION
            val consentPrompt =
                if (rejectedForConsent) permissionCoordinator.requestVpnConsentPrompt() else null
            if (consentPrompt != null) {
                vpnConsentRetryUsed = true
                _uiState.update { it.withValidationState(OnboardingValidationState.RequestingVpnConsent) }
                _effects.emit(OnboardingEffect.RequestVpnConsent(consentPrompt.intent))
                return
            }
            _uiState.update { current ->
                current.withValidationState(validationState)
            }
            if (validationState is OnboardingValidationState.Success) {
                setCurrentPage(OnboardingCompletionPageIndex)
            }
        }

        private fun invalidateValidation() {
            validationJob?.cancel()
            validationJob = null
            vpnConsentRetryUsed = false
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

        private companion object {
            const val SavedPageKey = "onboarding_page"
            const val SavedReturnPageKey = "onboarding_completion_return_page"
        }
    }
