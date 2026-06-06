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
    ) : OnboardingPage

    data class Setup(
        val kind: SetupPageKind,
        @param:StringRes override val titleRes: Int,
        @param:StringRes override val buttonLabelRes: Int,
    ) : OnboardingPage
}

internal enum class SetupPageKind {
    PersonaSelection,
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
        ),
        // -- Setup wizard pages --
        OnboardingPage.Setup(
            kind = SetupPageKind.PersonaSelection,
            titleRes = R.string.onboarding_persona_title,
            buttonLabelRes = R.string.onboarding_setup_next,
        ),
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
            titleRes = R.string.onboarding_setup_validate_title,
            buttonLabelRes = R.string.onboarding_setup_finish,
        ),
    )

internal const val OnboardingInfoPageCount = 1

/** Index of the DNS-selection page in [OnboardingPages]; used by the failed-validation "Change DNS" action. */
internal val OnboardingDnsPageIndex: Int =
    OnboardingPages.indexOfFirst { it is OnboardingPage.Setup && it.kind == SetupPageKind.DnsSelection }
