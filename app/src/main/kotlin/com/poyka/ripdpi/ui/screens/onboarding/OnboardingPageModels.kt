package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
import com.poyka.ripdpi.R

internal sealed interface OnboardingPage {
    val titleRes: Int
    val buttonLabelRes: Int

    data class Informational(
        @param:StringRes override val titleRes: Int,
        @param:StringRes val descriptionRes: Int,
        @param:StringRes override val buttonLabelRes: Int,
        val illustration: OnboardingIllustration,
    ) : OnboardingPage

    data class Setup(
        val kind: SetupPageKind,
        @param:StringRes override val titleRes: Int,
        @param:StringRes override val buttonLabelRes: Int,
    ) : OnboardingPage
}

internal enum class OnboardingIllustration {
    LocalFirst,
    Permission,
    Modes,
    Diagnostics,
    BypassModes,
    Privacy,
}

internal enum class SetupPageKind {
    ModeSelection,
    DnsSelection,
    ConnectionTest,
}

internal val OnboardingPages: List<OnboardingPage> =
    listOf(
        // -- Informational pages --
        OnboardingPage.Informational(
            titleRes = R.string.onboarding_local_first_title,
            descriptionRes = R.string.onboarding_local_first_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.LocalFirst,
        ),
        OnboardingPage.Informational(
            titleRes = R.string.onboarding_permission_title,
            descriptionRes = R.string.onboarding_permission_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.Permission,
        ),
        OnboardingPage.Informational(
            titleRes = R.string.onboarding_modes_title,
            descriptionRes = R.string.onboarding_modes_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.Modes,
        ),
        OnboardingPage.Informational(
            titleRes = R.string.onboarding_diagnostics_title,
            descriptionRes = R.string.onboarding_diagnostics_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.Diagnostics,
        ),
        OnboardingPage.Informational(
            titleRes = R.string.onboarding_bypass_modes_title,
            descriptionRes = R.string.onboarding_bypass_modes_body,
            buttonLabelRes = R.string.onboarding_continue,
            illustration = OnboardingIllustration.BypassModes,
        ),
        OnboardingPage.Informational(
            titleRes = R.string.onboarding_privacy_title,
            descriptionRes = R.string.onboarding_privacy_body,
            buttonLabelRes = R.string.onboarding_setup_next,
            illustration = OnboardingIllustration.Privacy,
        ),
        // -- Setup wizard pages --
        OnboardingPage.Setup(
            kind = SetupPageKind.ModeSelection,
            titleRes = R.string.onboarding_setup_mode_title,
            buttonLabelRes = R.string.onboarding_setup_next,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.DnsSelection,
            titleRes = R.string.onboarding_setup_dns_title,
            buttonLabelRes = R.string.onboarding_setup_next,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.ConnectionTest,
            titleRes = R.string.onboarding_setup_test_title,
            buttonLabelRes = R.string.onboarding_setup_finish,
        ),
    )

internal const val OnboardingInfoPageCount = 6
