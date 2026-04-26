package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import java.util.Locale

enum class DiagnosticsJurisdictionProfileAccess {
    ALLOWED,
    MANUAL_ONLY,
    BLOCKED,
}

data class DiagnosticsJurisdictionPolicyDecision(
    val access: DiagnosticsJurisdictionProfileAccess = DiagnosticsJurisdictionProfileAccess.ALLOWED,
    val requiresExplicitConsent: Boolean = false,
)

fun defaultDiagnosticsJurisdictionCountryCode(locale: Locale = Locale.getDefault()): String? =
    locale.country.takeIf(String::isNotBlank)?.lowercase(Locale.US)

fun ProfileSpecWire.resolveLegalSafetyPolicy(
    countryCode: String? = defaultDiagnosticsJurisdictionCountryCode(),
): DiagnosticsJurisdictionPolicyDecision =
    resolveLegalSafetyPolicy(
        intentBucket = intentBucket,
        legalSafety = legalSafety,
        regionTag = regionTag,
        countryCode = countryCode,
    )

fun DiagnosticsProfileProjection.resolveLegalSafetyPolicy(
    countryCode: String? = defaultDiagnosticsJurisdictionCountryCode(),
): DiagnosticsJurisdictionPolicyDecision =
    resolveLegalSafetyPolicy(
        intentBucket = intentBucket,
        legalSafety = legalSafety,
        regionTag = regionTag,
        countryCode = countryCode,
    )

private fun resolveLegalSafetyPolicy(
    intentBucket: DiagnosticsProfileIntentBucket,
    legalSafety: DiagnosticsLegalSafety,
    regionTag: String?,
    countryCode: String?,
): DiagnosticsJurisdictionPolicyDecision {
    if (!isPolicyScopedToCountry(regionTag, countryCode)) {
        return DiagnosticsJurisdictionPolicyDecision()
    }
    return when {
        legalSafety == DiagnosticsLegalSafety.UNSAFE ||
            intentBucket == DiagnosticsProfileIntentBucket.LAB_ONLY -> {
            DiagnosticsJurisdictionPolicyDecision(
                access = DiagnosticsJurisdictionProfileAccess.BLOCKED,
            )
        }

        legalSafety == DiagnosticsLegalSafety.SENSITIVE ||
            intentBucket == DiagnosticsProfileIntentBucket.MANUAL_SENSITIVE -> {
            DiagnosticsJurisdictionPolicyDecision(
                access = DiagnosticsJurisdictionProfileAccess.MANUAL_ONLY,
                requiresExplicitConsent = true,
            )
        }

        else -> {
            DiagnosticsJurisdictionPolicyDecision()
        }
    }
}

private fun isPolicyScopedToCountry(
    regionTag: String?,
    countryCode: String?,
): Boolean {
    val normalizedRegion =
        regionTag?.takeIf(String::isNotBlank)?.lowercase(Locale.US)
            ?: return true
    val normalizedCountry = countryCode?.takeIf(String::isNotBlank)?.lowercase(Locale.US)
    return normalizedCountry != null && normalizedRegion == normalizedCountry
}
