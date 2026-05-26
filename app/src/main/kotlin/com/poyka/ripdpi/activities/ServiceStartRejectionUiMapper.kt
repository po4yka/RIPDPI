package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.ServiceStartRejectionReason

internal fun ServiceStartRejectionReason.validationRecoveryKind(): OnboardingValidationRecoveryKind =
    when (this) {
        ServiceStartRejectionReason.NotificationsPermissionMissing -> {
            OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS
        }

        ServiceStartRejectionReason.VpnConsentMissing -> {
            OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION
        }

        is ServiceStartRejectionReason.ForegroundServiceBlocked -> {
            OnboardingValidationRecoveryKind.RETRY
        }
    }

internal fun ServiceStartRejectionReason.suggestedModeFor(mode: Mode): Mode? =
    when {
        this == ServiceStartRejectionReason.VpnConsentMissing && mode == Mode.VPN -> Mode.Proxy
        else -> null
    }

internal fun ServiceStartRejectionReason.displayMessage(stringResolver: StringResolver): String =
    when (this) {
        ServiceStartRejectionReason.NotificationsPermissionMissing -> {
            stringResolver.getString(R.string.permissions_notifications_denied)
        }

        ServiceStartRejectionReason.VpnConsentMissing -> {
            stringResolver.getString(R.string.permissions_vpn_error_body)
        }

        is ServiceStartRejectionReason.ForegroundServiceBlocked -> {
            message?.takeIf { it.isNotBlank() }
                ?: stringResolver.getString(R.string.onboarding_validation_failed_generic)
        }
    }
