package com.poyka.ripdpi.activities

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingModeValidationRunner
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingValidationResult
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

private val DefaultOnboardingPageCount = OnboardingPages.size

enum class OnboardingValidationRecoveryKind {
    RETRY,
    REQUEST_NOTIFICATIONS,
    REQUEST_VPN_PERMISSION,
    SWITCH_MODE,
}

sealed interface OnboardingValidationState {
    data object Idle : OnboardingValidationState

    data object RequestingNotifications : OnboardingValidationState

    data object RequestingVpnConsent : OnboardingValidationState

    data class StartingMode(
        val mode: Mode,
    ) : OnboardingValidationState

    data class RunningTrafficCheck(
        val mode: Mode,
    ) : OnboardingValidationState

    data class Success(
        val latencyMs: Long,
        val mode: Mode,
    ) : OnboardingValidationState

    data class Failed(
        val reason: String,
        val recoveryKind: OnboardingValidationRecoveryKind = OnboardingValidationRecoveryKind.RETRY,
        val suggestedMode: Mode? = null,
    ) : OnboardingValidationState
}

data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = DefaultOnboardingPageCount,
    val selectedMode: Mode = Mode.VPN,
    val selectedDnsProviderId: String = DnsProviderCloudflare,
    val validationState: OnboardingValidationState = OnboardingValidationState.Idle,
    val canFinishAnyway: Boolean = true,
    val canFinishKeepingRunning: Boolean = false,
    val canFinishDisconnected: Boolean = false,
)

sealed interface OnboardingEffect {
    data object OnboardingComplete : OnboardingEffect

    data object RequestNotificationsPermission : OnboardingEffect

    data class RequestVpnConsent(
        val intent: Intent,
    ) : OnboardingEffect
}

private enum class PendingValidationPermission {
    Notifications,
    VpnConsent,
}

private val OnboardingValidationState.isBusy: Boolean
    get() =
        when (this) {
            OnboardingValidationState.Idle,
            is OnboardingValidationState.Success,
            is OnboardingValidationState.Failed,
            -> false

            OnboardingValidationState.RequestingNotifications,
            OnboardingValidationState.RequestingVpnConsent,
            is OnboardingValidationState.StartingMode,
            is OnboardingValidationState.RunningTrafficCheck,
            -> true
        }

private fun OnboardingUiState.withValidationState(validationState: OnboardingValidationState): OnboardingUiState =
    copy(
        validationState = validationState,
        canFinishAnyway = !validationState.isBusy,
        canFinishKeepingRunning = validationState is OnboardingValidationState.Success,
        canFinishDisconnected = validationState is OnboardingValidationState.Success,
    )

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val validationRunner: OnboardingModeValidationRunner,
        private val permissionStatusProvider: PermissionStatusProvider,
        private val permissionPlatformBridge: PermissionPlatformBridge,
        private val stringResolver: StringResolver,
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
        private var pendingValidationPermission: PendingValidationPermission? = null

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
                persistSelectionsNow(mode = mode, dnsProviderId = current.selectedDnsProviderId)
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
                persistSelectionsNow(mode = current.selectedMode, dnsProviderId = providerId)
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
                    persistSelectionsNow(
                        mode = currentState.selectedMode,
                        dnsProviderId = currentState.selectedDnsProviderId,
                    )

                    val snapshot = permissionStatusProvider.currentSnapshot()
                    if (snapshot.notifications.requiresValidationPrompt()) {
                        pendingValidationPermission = PendingValidationPermission.Notifications
                        _uiState.update {
                            it.withValidationState(OnboardingValidationState.RequestingNotifications)
                        }
                        _effects.emit(OnboardingEffect.RequestNotificationsPermission)
                        return@launch
                    }
                    if (currentState.selectedMode == Mode.VPN &&
                        snapshot.vpnConsent.requiresValidationPrompt()
                    ) {
                        val intent = permissionPlatformBridge.prepareVpnPermissionIntent()
                        if (intent == null) {
                            pendingValidationPermission = null
                            performValidation(currentState.selectedMode)
                            return@launch
                        }
                        pendingValidationPermission = PendingValidationPermission.VpnConsent
                        _uiState.update {
                            it.withValidationState(OnboardingValidationState.RequestingVpnConsent)
                        }
                        _effects.emit(OnboardingEffect.RequestVpnConsent(intent))
                        return@launch
                    }
                    pendingValidationPermission = null
                    performValidation(currentState.selectedMode)
                }
        }

        fun onNotificationPermissionResult(result: PermissionResult) {
            if (pendingValidationPermission != PendingValidationPermission.Notifications) {
                return
            }
            when (result) {
                PermissionResult.Granted -> {
                    pendingValidationPermission = null
                    validationJob?.cancel()
                    validationJob =
                        viewModelScope.launch {
                            performValidation(_uiState.value.selectedMode)
                        }
                }

                PermissionResult.Denied,
                PermissionResult.DeniedPermanently,
                PermissionResult.ReturnedFromSettings,
                -> {
                    pendingValidationPermission = null
                    _uiState.update {
                        it.withValidationState(
                            OnboardingValidationState.Failed(
                                reason =
                                    stringResolver.getString(
                                        R.string.onboarding_validation_notifications_required,
                                    ),
                                recoveryKind = OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
                            ),
                        )
                    }
                }
            }
        }

        fun onVpnPermissionResult(result: PermissionResult) {
            if (pendingValidationPermission != PendingValidationPermission.VpnConsent) {
                return
            }
            when (result) {
                PermissionResult.Granted -> {
                    pendingValidationPermission = null
                    validationJob?.cancel()
                    validationJob =
                        viewModelScope.launch {
                            performValidation(_uiState.value.selectedMode)
                        }
                }

                PermissionResult.Denied,
                PermissionResult.DeniedPermanently,
                PermissionResult.ReturnedFromSettings,
                -> {
                    pendingValidationPermission = null
                    _uiState.update {
                        it.withValidationState(
                            OnboardingValidationState.Failed(
                                reason =
                                    stringResolver.getString(
                                        R.string.onboarding_validation_vpn_permission_denied,
                                    ),
                                recoveryKind = OnboardingValidationRecoveryKind.SWITCH_MODE,
                                suggestedMode = Mode.Proxy,
                            ),
                        )
                    }
                }
            }
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
            validationRunner.retainActiveValidation()
            completeOnboarding()
        }

        fun finishDisconnected() {
            validationRunner.stopActiveValidation()
            completeOnboarding()
        }

        fun finishAnyway() {
            validationRunner.stopActiveValidation()
            completeOnboarding()
        }

        fun skip() {
            validationRunner.stopActiveValidation()
            completeOnboarding()
        }

        override fun onCleared() {
            validationJob?.cancel()
            validationRunner.stopActiveValidation()
            super.onCleared()
        }

        private fun observeOnboardingSelections() {
            viewModelScope.launch {
                appSettingsRepository.settings.collect { settings ->
                    if (settings.onboardingComplete) {
                        return@collect
                    }
                    val selectedMode =
                        Mode.fromString(settings.ripdpiMode.ifEmpty { Mode.VPN.preferenceValue })
                    val dnsProviderId =
                        settings.dnsProviderId.ifEmpty { DnsProviderCloudflare }
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
        }

        private suspend fun performValidation(mode: Mode) {
            val result =
                validationRunner.validate(mode = mode) { progress ->
                    _uiState.update { it.withValidationState(progress) }
                }
            _uiState.update { current ->
                when (result) {
                    is OnboardingValidationResult.Success -> {
                        current.withValidationState(
                            OnboardingValidationState.Success(
                                latencyMs = result.latencyMs,
                                mode = mode,
                            ),
                        )
                    }

                    is OnboardingValidationResult.Failed -> {
                        current.withValidationState(
                            OnboardingValidationState.Failed(
                                reason = result.reason,
                                recoveryKind =
                                    if (result.suggestedMode != null) {
                                        OnboardingValidationRecoveryKind.SWITCH_MODE
                                    } else {
                                        OnboardingValidationRecoveryKind.RETRY
                                    },
                                suggestedMode = result.suggestedMode,
                            ),
                        )
                    }
                }
            }
        }

        private fun invalidateValidation() {
            validationJob?.cancel()
            validationJob = null
            pendingValidationPermission = null
            validationRunner.stopActiveValidation()
        }

        private suspend fun persistSelectionsNow(
            mode: Mode,
            dnsProviderId: String,
        ) {
            appSettingsRepository.update {
                setRipdpiMode(mode.preferenceValue)
                setDnsProviderId(dnsProviderId)
            }
        }

        private fun completeOnboarding() {
            validationJob?.cancel()
            validationJob = null
            pendingValidationPermission = null
            val state = _uiState.value
            viewModelScope.launch {
                appSettingsRepository.update {
                    setOnboardingComplete(true)
                    setRipdpiMode(state.selectedMode.preferenceValue)
                    setDnsProviderId(state.selectedDnsProviderId)
                }
                _effects.emit(OnboardingEffect.OnboardingComplete)
            }
        }
    }

private fun PermissionStatus.requiresValidationPrompt(): Boolean =
    this != PermissionStatus.Granted && this != PermissionStatus.NotApplicable
