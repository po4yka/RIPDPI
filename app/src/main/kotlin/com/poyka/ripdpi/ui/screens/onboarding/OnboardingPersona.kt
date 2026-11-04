package com.poyka.ripdpi.ui.screens.onboarding

/**
 * Typed persona for the onboarding interface-selection step. The persisted representation
 * (AppSettings.uiPersona / OnboardingUiState.selectedPersona) stays a String; [id] / [fromId] map
 * across that boundary, and the stored id of [Guided] stays "simple" for existing installs.
 * [fromId] is total and defaults to [Guided], preserving the existing
 * "anything not 'advanced' -> simple" normalization used by OnboardingSettingsCoordinator,
 * OnboardingViewModel, and SettingsCustomizationActions.
 */
internal enum class OnboardingPersona {
    Guided,
    Advanced,
    ;

    val id: String
        get() =
            when (this) {
                Guided -> "simple"
                Advanced -> "advanced"
            }

    companion object {
        fun fromId(id: String): OnboardingPersona = if (id.equals(Advanced.id, ignoreCase = true)) Advanced else Guided
    }
}
