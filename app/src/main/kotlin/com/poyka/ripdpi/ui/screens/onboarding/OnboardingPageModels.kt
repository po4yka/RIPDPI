package com.poyka.ripdpi.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiIcons

internal sealed interface OnboardingPage {
    val icon: ImageVector
    val titleRes: Int
    val buttonLabelRes: Int

    data class Informational(
        override val icon: ImageVector,
        @param:StringRes override val titleRes: Int,
        @param:StringRes val descriptionRes: Int,
        @param:StringRes override val buttonLabelRes: Int,
    ) : OnboardingPage

    data class Setup(
        override val icon: ImageVector,
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
            icon = RipDpiIcons.Shield,
            titleRes = R.string.onboarding_local_first_title,
            descriptionRes = R.string.onboarding_local_first_body,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        // -- Setup wizard pages --
        OnboardingPage.Setup(
            kind = SetupPageKind.PersonaSelection,
            icon = RipDpiIcons.Config,
            titleRes = R.string.onboarding_persona_title,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.ModeSelection,
            icon = RipDpiIcons.Vpn,
            titleRes = R.string.onboarding_setup_mode_title,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.ConnectionTest,
            icon = RipDpiIcons.NetworkCheck,
            titleRes = R.string.onboarding_setup_validate_title,
            buttonLabelRes = R.string.onboarding_setup_finish,
        ),
    )

internal const val OnboardingInfoPageCount = 1
