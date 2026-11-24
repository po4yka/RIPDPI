package com.poyka.ripdpi.ui.screens.onboarding

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
        private var notificationPromptOffered = false

        internal suspend fun nextValidationPrompt(mode: Mode): OnboardingPermissionPrompt? {
            val snapshot = permissionStatusProvider.currentSnapshot()
            if (shouldOfferNotificationPrompt(snapshot.notifications)) {
                notificationPromptOffered = true
                pendingValidationPermission = PendingValidationPermission.Notifications
                return OnboardingPermissionPrompt.Notifications
            }
            return if (mode == Mode.VPN && snapshot.vpnConsent.requiresValidationPrompt()) {
                requestVpnConsentPrompt()
            } else {
                pendingValidationPermission = null
                null
            }
        }

        internal fun notificationsPromptExpected(): Boolean =
            shouldOfferNotificationPrompt(permissionStatusProvider.currentSnapshot().notifications)

        private fun shouldOfferNotificationPrompt(status: PermissionStatus): Boolean =
            !notificationPromptOffered &&
                (status == PermissionStatus.RequiresSystemPrompt || status == PermissionStatus.Denied)

        internal fun batterySettingsIntent(): Intent = permissionPlatformBridge.createBatteryOptimizationIntent()

        internal fun vpnSettingsIntent(): Intent = permissionPlatformBridge.createVpnSettingsIntent()

        /**
         * Builds a VPN-consent prompt on demand. Used to recover when the service authoritatively
         * rejected the start for missing consent even though [nextValidationPrompt] did not pre-empt
         * it — `VpnService.prepare()` can report consent granted on one call and missing on the next
         * (e.g. an `ACTIVATE_VPN` app-op left in a transient state), so the advisory pre-check and the
         * service-side check disagree. Returns null when no consent intent is available, in which case
         * the caller keeps the failure state.
         */
        internal fun requestVpnConsentPrompt(): OnboardingPermissionPrompt.VpnConsent? {
            val prompt =
                permissionPlatformBridge
                    .prepareVpnPermissionIntent()
                    ?.let(OnboardingPermissionPrompt::VpnConsent)
            pendingValidationPermission =
                if (prompt != null) PendingValidationPermission.VpnConsent else null
            return prompt
        }

        internal fun completeNotificationRequest(): Boolean {
            if (pendingValidationPermission != PendingValidationPermission.Notifications) return false
            pendingValidationPermission = null
            return true
        }

        internal fun onVpnPermissionResult(result: PermissionResult): OnboardingPermissionOutcome? {
            if (pendingValidationPermission != PendingValidationPermission.VpnConsent) {
                return null
            }
            pendingValidationPermission = null
            return when (result) {
                PermissionResult.Granted -> OnboardingPermissionOutcome.ContinueValidation

                PermissionResult.Cancelled,
                PermissionResult.Denied,
                PermissionResult.DeniedPermanently,
                PermissionResult.ReturnedFromSettings,
                -> OnboardingPermissionOutcome.Failed(vpnPermissionFailureState())
            }
        }

        internal fun clearPendingPermission() {
            pendingValidationPermission = null
        }

        private fun vpnPermissionFailureState(): OnboardingValidationState.Failed =
            OnboardingValidationState.Failed(
                reason = stringResolver.getString(R.string.onboarding_validation_vpn_permission_denied),
                recoveryKind = OnboardingValidationRecoveryKind.SWITCH_MODE,
                suggestedMode = Mode.Proxy,
            )
    }

private fun PermissionStatus.requiresValidationPrompt(): Boolean =
    this != PermissionStatus.Granted && this != PermissionStatus.NotApplicable
