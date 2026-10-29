package com.poyka.ripdpi.ui.journeys.home

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import org.junit.Test
import javax.inject.Inject

private class OnboardingPage(
    provider: SemanticsNodeInteractionsProvider,
) : ComposeScreen<OnboardingPage>(provider, { hasTestTag(RipDpiTestTags.screen(Route.Onboarding)) }) {
    fun tagged(tag: String): KNode = onNode { hasTestTag(tag) }

    val skip = tagged(RipDpiTestTags.OnboardingSkip)
    val next = tagged(RipDpiTestTags.OnboardingContinue)
    val personaSimple = tagged(RipDpiTestTags.OnboardingPersonaSimple)
    val personaAdvanced = tagged(RipDpiTestTags.OnboardingPersonaAdvanced)
    val modeVpn = tagged(RipDpiTestTags.OnboardingModeVpn)
    val modeProxy = tagged(RipDpiTestTags.OnboardingModeProxy)
    val validate = tagged(RipDpiTestTags.OnboardingValidateAction)
    val skipTest = tagged(RipDpiTestTags.OnboardingSkipTest)
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
        persona: (OnboardingPage) -> KNode,
        mode: (OnboardingPage) -> KNode,
    ) = run {
        ComposeScreen.onComposeScreen<OnboardingPage>(composeRule) {
            step("Privacy page continues to the persona page") { next.performClick() }
            step("Pick the persona and continue") {
                composeRule.waitForTag(RipDpiTestTags.OnboardingPersonaSimple)
                persona(this@onComposeScreen).performClick()
                next.performClick()
            }
            step("Pick the traffic mode and continue") {
                composeRule.waitForTag(RipDpiTestTags.OnboardingModeVpn)
                mode(this@onComposeScreen).performClick()
                next.performClick()
            }
            step("Connection test page offers the validation action, skip the test") {
                composeRule.waitForTag(RipDpiTestTags.OnboardingValidateAction)
                validate.assertIsDisplayed()
                skipTest.performClick()
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
            ComposeScreen.onComposeScreen<OnboardingPage>(composeRule) {
                step("Skip setup on the intro page") { skip.performClick() }
            }
            step("Home appears and onboarding is persisted as complete") {
                composeRule.waitForSettings(appSettingsRepository) { it.onboardingComplete }
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
            }
        }
}
