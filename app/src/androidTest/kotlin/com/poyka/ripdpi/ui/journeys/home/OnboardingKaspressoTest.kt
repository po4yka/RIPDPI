package com.poyka.ripdpi.ui.journeys.home

import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.journeys.waitForTag
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import javax.inject.Inject

private class OnboardingPage(
    rule: JourneyRule,
) : JourneyPage<OnboardingPage>(rule, RipDpiTestTags.screen(Route.Onboarding)) {
    val skip = RipDpiTestTags.OnboardingSkip
    val next = RipDpiTestTags.OnboardingContinue
    val personaSimple = RipDpiTestTags.OnboardingPersonaSimple
    val personaAdvanced = RipDpiTestTags.OnboardingPersonaAdvanced
    val modeVpn = RipDpiTestTags.OnboardingModeVpn
    val modeProxy = RipDpiTestTags.OnboardingModeProxy
    val validate = RipDpiTestTags.OnboardingValidateAction
    val skipTest = RipDpiTestTags.OnboardingSkipTest
}

@HiltAndroidTest
class OnboardingKaspressoTest :
    RipDpiKaspressoTest(
        // No start route: the incomplete-onboarding startup destination is the real first-run path.
        automationLaunchIntent(dataPreset = AutomationDataPreset.CleanHome),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    private fun walkOnboarding(
        persona: (OnboardingPage) -> String,
        mode: (OnboardingPage) -> String,
    ) = run {
        with(OnboardingPage(composeRule)) {
            step("Privacy page continues to the persona page") { click(next) }
            step("Pick the persona and continue") {
                waitFor(personaSimple)
                click(persona(this))
                click(next)
            }
            step("Pick the traffic mode and continue") {
                waitFor(modeVpn)
                click(mode(this))
                click(next)
            }
            step("Connection test page offers the validation action, skip the test") {
                waitFor(validate)
                node(validate).assertIsDisplayed()
                click(skipTest)
            }
        }
    }

    @Test
    fun advancedPersonaWithProxyModeCompletesOnboardingAndPersistsChoices() {
        walkOnboarding(persona = { it.personaAdvanced }, mode = { it.modeProxy })
        composeRule.waitForSettings(appSettingsRepository) {
            it.onboardingComplete && it.uiPersona == "advanced" && it.ripdpiMode == Mode.Proxy.preferenceValue
        }
        composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
    }

    @Test
    fun simplePersonaWithVpnModeCompletesOnboardingAndPersistsChoices() {
        walkOnboarding(persona = { it.personaSimple }, mode = { it.modeVpn })
        composeRule.waitForSettings(appSettingsRepository) {
            it.onboardingComplete && it.uiPersona == "simple" && it.ripdpiMode == Mode.VPN.preferenceValue
        }
        composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
    }

    @Test
    fun skippingFromTheIntroCompletesOnboarding() =
        run {
            with(OnboardingPage(composeRule)) {
                step("Skip setup on the intro page") { click(skip) }
            }
            step("Home appears and onboarding is persisted as complete") {
                composeRule.waitForSettings(appSettingsRepository) { it.onboardingComplete }
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
            }
        }
}
