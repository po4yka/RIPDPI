package com.poyka.ripdpi.ui.screens.onboarding

import android.content.Intent
import com.poyka.ripdpi.data.Mode

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
    Volume,
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
        val mode: Mode,
        val dnsSummary: String,
        val volumeTested: Boolean,
    ) : OnboardingValidationState

    data class Failed(
        val reason: String,
        val recoveryKind: OnboardingValidationRecoveryKind = OnboardingValidationRecoveryKind.RETRY,
        val suggestedMode: Mode? = null,
        val failedStep: OnboardingValidationStep? = null,
        val transferredBytes: Long? = null,
    ) : OnboardingValidationState
}

data class OnboardingUiState(
    val currentPage: Int = 0,
    val totalPages: Int = DefaultOnboardingPageCount,
    // A packaged preset made the choices: the steps come from the preset page list.
    val presetApplied: Boolean = false,
    val selectedMode: Mode = Mode.VPN,
    val selectedPersona: String = "simple",
    val selectedDnsProviderId: String = OnboardingDnsSystemId,
    // The step that Back returns to from the completion step: an exit can reach it from any step.
    val completionReturnPage: Int? = null,
    // Android will ask for the notification permission when the test starts, so the step explains it first.
    val notificationsNoteVisible: Boolean = false,
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

    /** Opens a system settings screen from the guidance step. It requests nothing. */
    data class OpenSystemSettings(
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
        // A preset holds its mode and provider. A mode switch would drop the provider, so none is offered.
        validationState =
            if (presetApplied && validationState is OnboardingValidationState.Failed) {
                validationState.copy(suggestedMode = null)
            } else {
                validationState
            },
        canFinishAnyway = !validationState.isBusy,
        canFinishKeepingRunning = validationState is OnboardingValidationState.Success,
        canFinishDisconnected = validationState is OnboardingValidationState.Success,
    )
