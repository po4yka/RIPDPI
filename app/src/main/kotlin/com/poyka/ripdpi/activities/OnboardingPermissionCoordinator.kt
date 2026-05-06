package com.poyka.ripdpi.activities

import android.content.Intent
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.permissions.PermissionStatusProvider
import com.poyka.ripdpi.platform.PermissionPlatformBridge
import com.poyka.ripdpi.platform.StringResolver
import javax.inject.Inject

internal sealed interface OnboardingPermissionPrompt {
    data object Notifications : OnboardingPermissionPrompt

    data class VpnConsent(
        val intent: Intent,
    ) : OnboardingPermissionPrompt
}

internal sealed interface OnboardingPermissionOutcome {
    data object ContinueValidation : OnboardingPermissionOutcome

    data class Failed(
        val state: OnboardingValidationState.Failed,
    ) : OnboardingPermissionOutcome
}

private enum class PendingValidationPermission {
    Notifications,
    VpnConsent,
}

class OnboardingPermissionCoordinator
    @Inject
    constructor(
        private val permissionStatusProvider: PermissionStatusProvider,
        private val permissionPlatformBridge: PermissionPlatformBridge,
        private val stringResolver: StringResolver,
    ) {
        private var pendingValidationPermission: PendingValidationPermission? = null

        internal suspend fun nextValidationPrompt(mode: Mode): OnboardingPermissionPrompt? {
            val snapshot = permissionStatusProvider.currentSnapshot()
            val prompt =
                if (snapshot.notifications.requiresValidationPrompt()) {
                    OnboardingPermissionPrompt.Notifications
                } else if (mode == Mode.VPN && snapshot.vpnConsent.requiresValidationPrompt()) {
                    permissionPlatformBridge.prepareVpnPermissionIntent()?.let(OnboardingPermissionPrompt::VpnConsent)
                } else {
                    null
                }
            when (prompt) {
                OnboardingPermissionPrompt.Notifications -> {
                    pendingValidationPermission = PendingValidationPermission.Notifications
                }

                is OnboardingPermissionPrompt.VpnConsent -> {
                    pendingValidationPermission = PendingValidationPermission.VpnConsent
                }

                null -> {
                    pendingValidationPermission = null
                }
            }
            return prompt
        }

        internal fun onNotificationPermissionResult(result: PermissionResult): OnboardingPermissionOutcome? {
            if (pendingValidationPermission != PendingValidationPermission.Notifications) {
                return null
            }
            pendingValidationPermission = null
            return when (result) {
                PermissionResult.Granted -> OnboardingPermissionOutcome.ContinueValidation

                PermissionResult.Denied,
                PermissionResult.DeniedPermanently,
                PermissionResult.ReturnedFromSettings,
                -> OnboardingPermissionOutcome.Failed(notificationsFailureState())
            }
        }

        internal fun onVpnPermissionResult(result: PermissionResult): OnboardingPermissionOutcome? {
            if (pendingValidationPermission != PendingValidationPermission.VpnConsent) {
                return null
            }
            pendingValidationPermission = null
            return when (result) {
                PermissionResult.Granted -> OnboardingPermissionOutcome.ContinueValidation

                PermissionResult.Denied,
                PermissionResult.DeniedPermanently,
                PermissionResult.ReturnedFromSettings,
                -> OnboardingPermissionOutcome.Failed(vpnPermissionFailureState())
            }
        }

        internal fun clearPendingPermission() {
            pendingValidationPermission = null
        }

        private fun notificationsFailureState(): OnboardingValidationState.Failed =
            OnboardingValidationState.Failed(
                reason = stringResolver.getString(R.string.onboarding_validation_notifications_required),
                recoveryKind = OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS,
            )

        private fun vpnPermissionFailureState(): OnboardingValidationState.Failed =
            OnboardingValidationState.Failed(
                reason = stringResolver.getString(R.string.onboarding_validation_vpn_permission_denied),
                recoveryKind = OnboardingValidationRecoveryKind.SWITCH_MODE,
                suggestedMode = Mode.Proxy,
            )
    }

private fun PermissionStatus.requiresValidationPrompt(): Boolean =
    this != PermissionStatus.Granted && this != PermissionStatus.NotApplicable
