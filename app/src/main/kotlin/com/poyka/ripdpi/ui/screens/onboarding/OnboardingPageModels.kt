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
        /** The privacy and on-device guarantee lists under the header. */
        val guarantees: Boolean = false,
    ) : OnboardingPage

    data class Setup(
        override val icon: ImageVector,
        val kind: SetupPageKind,
        @param:StringRes override val titleRes: Int,
        @param:StringRes override val buttonLabelRes: Int,
        @param:StringRes val descriptionRes: Int? = null,
    ) : OnboardingPage
}

internal enum class SetupPageKind {
    Welcome,
    PersonaSelection,
    ModeSelection,
    DnsSelection,
    BackgroundGuidance,
    ConnectionTest,
    Completion,
}

internal val OnboardingPages: List<OnboardingPage> =
    listOf(
        // -- Welcome and privacy --
        OnboardingPage.Setup(
            kind = SetupPageKind.Welcome,
            icon = RipDpiIcons.Connected,
            titleRes = R.string.onboarding_welcome_title,
            descriptionRes = R.string.onboarding_welcome_body,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        OnboardingPage.Informational(
            icon = RipDpiIcons.Shield,
            titleRes = R.string.onboarding_local_first_title,
            descriptionRes = R.string.onboarding_local_first_body,
            buttonLabelRes = R.string.onboarding_continue,
            guarantees = true,
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
            titleRes = R.string.config_scope_title,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.DnsSelection,
            icon = RipDpiIcons.Dns,
            titleRes = R.string.onboarding_dns_title,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.BackgroundGuidance,
            icon = RipDpiIcons.Timer,
            titleRes = R.string.onboarding_background_title,
            buttonLabelRes = R.string.onboarding_continue,
        ),
        OnboardingPage.Setup(
            kind = SetupPageKind.ConnectionTest,
            icon = RipDpiIcons.NetworkCheck,
            titleRes = R.string.onboarding_setup_validate_title,
            descriptionRes = R.string.onboarding_test_idle_body,
            buttonLabelRes = R.string.onboarding_setup_finish,
        ),
        // Last: every exit passes through it, and the badge shows its check mark after a passed test.
        OnboardingPage.Setup(
            kind = SetupPageKind.Completion,
            icon = RipDpiIcons.Check,
            titleRes = R.string.onboarding_done_title,
            buttonLabelRes = R.string.app_picker_done,
        ),
    )

/**
 * A packaged preset already made the three choices, so each choice step explains the app instead. The list
 * keeps its length, its order, and its icons, so the step indexes, the badge, and the scenes serve both lists.
 */
private val PresetOnboardingPages: List<OnboardingPage> =
    OnboardingPages.map { page ->
        when ((page as? OnboardingPage.Setup)?.kind) {
            SetupPageKind.PersonaSelection -> {
                presetPage(page.icon, R.string.onboarding_preset_ready_title, R.string.onboarding_preset_ready_body)
            }

            SetupPageKind.ModeSelection -> {
                presetPage(page.icon, R.string.onboarding_preset_connect_title, R.string.onboarding_preset_connect_body)
            }

            SetupPageKind.DnsSelection -> {
                presetPage(page.icon, R.string.onboarding_preset_trouble_title, R.string.onboarding_preset_trouble_body)
            }

            else -> {
                page
            }
        }
    }

private fun presetPage(
    icon: ImageVector,
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
) = OnboardingPage.Informational(icon, titleRes, descriptionRes, R.string.onboarding_continue)

internal fun onboardingPages(presetApplied: Boolean): List<OnboardingPage> =
    if (presetApplied) PresetOnboardingPages else OnboardingPages

/** The welcome and the privacy step, which come before the first choice. */
internal const val OnboardingInfoPageCount = 2

internal fun onboardingPageIndex(kind: SetupPageKind): Int =
    OnboardingPages.indexOfFirst { it is OnboardingPage.Setup && it.kind == kind }

internal val OnboardingTestPageIndex = onboardingPageIndex(SetupPageKind.ConnectionTest)
internal val OnboardingCompletionPageIndex = onboardingPageIndex(SetupPageKind.Completion)
