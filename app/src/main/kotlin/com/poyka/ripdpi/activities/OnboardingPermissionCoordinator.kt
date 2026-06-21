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

    data class NotificationSettings(
        val intent: Intent,
    ) : OnboardingPermissionPrompt

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
    NotificationSettings,
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
        private var notificationsRequireSettings: Boolean = false

        internal suspend fun nextValidationPrompt(mode: Mode): OnboardingPermissionPrompt? {
            val snapshot = permissionStatusProvider.currentSnapshot()
            val notificationsPrompt = snapshot.notifications.validationPrompt()
            val prompt =
                notificationsPrompt ?: if (mode == Mode.VPN && snapshot.vpnConsent.requiresValidationPrompt()) {
                    permissionPlatformBridge.prepareVpnPermissionIntent()?.let(OnboardingPermissionPrompt::VpnConsent)
                } else {
                    null
                }
            when (prompt) {
                OnboardingPermissionPrompt.Notifications -> {
                    pendingValidationPermission = PendingValidationPermission.Notifications
                }

                is OnboardingPermissionPrompt.NotificationSettings -> {
                    pendingValidationPermission = PendingValidationPermission.NotificationSettings
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

        private fun PermissionStatus.validationPrompt(): OnboardingPermissionPrompt? =
            when {
                this == PermissionStatus.Granted || this == PermissionStatus.NotApplicable -> {
                    null
                }

                notificationsRequireSettings || this == PermissionStatus.RequiresSettings -> {
                    OnboardingPermissionPrompt.NotificationSettings(permissionPlatformBridge.createAppSettingsIntent())
                }

                requiresValidationPrompt() -> {
                    OnboardingPermissionPrompt.Notifications
                }

                else -> {
                    null
                }
            }

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

        internal fun onNotificationPermissionResult(result: PermissionResult): OnboardingPermissionOutcome? {
            if (!pendingValidationPermission.isNotificationPermission()) {
                return null
            }
            pendingValidationPermission = null
            return when (result) {
                PermissionResult.Granted -> {
                    notificationsRequireSettings = false
                    OnboardingPermissionOutcome.ContinueValidation
                }

                PermissionResult.Denied -> {
                    OnboardingPermissionOutcome.Failed(notificationsFailureState())
                }

                PermissionResult.DeniedPermanently -> {
                    notificationsRequireSettings = true
                    OnboardingPermissionOutcome.Failed(notificationSettingsFailureState())
                }

                PermissionResult.ReturnedFromSettings -> {
                    if (permissionStatusProvider.currentSnapshot().notifications == PermissionStatus.Granted) {
                        notificationsRequireSettings = false
                        OnboardingPermissionOutcome.ContinueValidation
                    } else {
                        notificationsRequireSettings = true
                        OnboardingPermissionOutcome.Failed(notificationSettingsFailureState())
                    }
                }
            }
        }

        private fun PendingValidationPermission?.isNotificationPermission(): Boolean =
            this == PendingValidationPermission.Notifications ||
                this == PendingValidationPermission.NotificationSettings

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

        private fun notificationSettingsFailureState(): OnboardingValidationState.Failed =
            OnboardingValidationState.Failed(
                reason = stringResolver.getString(R.string.onboarding_validation_notifications_required),
                recoveryKind = OnboardingValidationRecoveryKind.REQUEST_NOTIFICATION_SETTINGS,
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
