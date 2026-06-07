package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingDnsSystemId
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages

private val DefaultOnboardingPageCount = OnboardingPages.size

enum class OnboardingValidationRecoveryKind {
    RETRY,
    REQUEST_NOTIFICATIONS,
    REQUEST_VPN_PERMISSION,
    SWITCH_MODE,
}

enum class OnboardingValidationStep {
    Tunnel,
    Dns,
    Connectivity,
}

sealed interface OnboardingValidationState {
    data object Idle : OnboardingValidationState

    data object RequestingNotifications : OnboardingValidationState

    data object RequestingVpnConsent : OnboardingValidationState

    data class StartingMode(
        val mode: Mode,
    ) : OnboardingValidationState

    data class CheckingDns(
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
        val failedStep: OnboardingValidationStep? = null,
    ) : OnboardingValidationState
}

data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = DefaultOnboardingPageCount,
    val selectedMode: Mode = Mode.VPN,
    val selectedPersona: String = "simple",
    val selectedDnsProviderId: String = OnboardingDnsSystemId,
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

internal val OnboardingValidationState.isBusy: Boolean
    get() =
        when (this) {
            OnboardingValidationState.Idle,
            is OnboardingValidationState.Success,
            is OnboardingValidationState.Failed,
            -> false

            OnboardingValidationState.RequestingNotifications,
            OnboardingValidationState.RequestingVpnConsent,
            is OnboardingValidationState.StartingMode,
            is OnboardingValidationState.CheckingDns,
            is OnboardingValidationState.RunningTrafficCheck,
            -> true
        }

internal fun OnboardingUiState.withValidationState(validationState: OnboardingValidationState): OnboardingUiState =
    copy(
        validationState = validationState,
        canFinishAnyway = !validationState.isBusy,
        canFinishKeepingRunning = validationState is OnboardingValidationState.Success,
        canFinishDisconnected = validationState is OnboardingValidationState.Success,
    )
