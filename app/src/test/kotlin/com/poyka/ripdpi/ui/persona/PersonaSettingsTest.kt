package com.poyka.ripdpi.ui.persona

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.settings.state.toUiState
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPage
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import com.poyka.ripdpi.ui.screens.onboarding.SetupPageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaSettingsTest {
    @Test
    fun `default persona is simple`() {
        assertEquals("simple", AppSettingsSerializer.defaultValue.uiPersona)
        assertEquals("simple", AppSettingsSerializer.defaultValue.toUiState().uiPersona)
    }

    @Test
    fun `advanced persona maps to settings ui state`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setUiPersona("advanced")
                .build()

        assertEquals("advanced", settings.toUiState().uiPersona)
    }

    @Test
    fun `onboarding includes persona before mode selection`() {
        val setupKinds = OnboardingPages.mapNotNull { page -> page as? OnboardingPage.Setup }.map { it.kind }

        assertTrue(setupKinds.indexOf(SetupPageKind.PersonaSelection) < setupKinds.indexOf(SetupPageKind.ModeSelection))
    }
}
