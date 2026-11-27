package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingSceneStageTest {
    private fun scene(
        test: OnboardingValidationState = OnboardingValidationState.Idle,
        statesTest: Boolean = true,
        uiState: OnboardingUiState = OnboardingUiState(),
    ) = onboardingSceneState(uiState.copy(validationState = test), dns = "Resolver", statesTest = statesTest)

    @Test
    fun `the scenes take the choices of the setup`() {
        val chosen = OnboardingUiState(selectedMode = Mode.Proxy, selectedPersona = OnboardingPersona.Advanced.id)

        assertEquals(OnboardingSceneState(dns = "Resolver"), scene())
        assertEquals(OnboardingSceneState(advanced = true, proxy = true, dns = "Resolver"), scene(uiState = chosen))
    }

    @Test
    fun `the running test is at the stage of the checklist, and a permission request is its first stage`() {
        val first = OnboardingSceneState(dns = "Resolver", running = true, stage = 0)

        assertEquals(first, scene(OnboardingValidationState.RequestingNotifications))
        assertEquals(first, scene(OnboardingValidationState.RequestingVpnConsent))
        assertEquals(first, scene(OnboardingValidationState.StartingMode(Mode.VPN)))
        assertEquals(first.copy(stage = 1), scene(OnboardingValidationState.CheckingDns(Mode.VPN)))
        assertEquals(first.copy(stage = 2), scene(OnboardingValidationState.RunningTrafficCheck(Mode.Proxy)))
    }

    @Test
    fun `a passed test has every stage complete`() {
        val passed = OnboardingValidationState.Success(mode = Mode.VPN, dnsSummary = "", volumeTested = true)

        assertEquals(OnboardingSceneState(dns = "Resolver", passed = true, stage = 3), scene(passed))
    }

    @Test
    fun `a failed test stops at its stage, and a failure with no stage is a tunnel failure`() {
        fun failedAt(step: OnboardingValidationStep?) =
            scene(OnboardingValidationState.Failed(reason = "", failedStep = step))

        assertEquals(
            OnboardingSceneState(dns = "Resolver", failedAt = 0, stage = 0),
            failedAt(OnboardingValidationStep.Tunnel),
        )
        assertEquals(
            OnboardingSceneState(dns = "Resolver", failedAt = 1, stage = 1),
            failedAt(OnboardingValidationStep.Dns),
        )
        assertEquals(OnboardingSceneState(dns = "Resolver", failedAt = 0, stage = 0), failedAt(null))
        assertEquals(
            OnboardingSceneState(dns = "Resolver", failedAt = 2, stage = 2),
            failedAt(OnboardingValidationStep.Connectivity),
        )
    }

    @Test
    fun `a step that does not state the test shows it as not started`() {
        val failed = OnboardingValidationState.Failed(reason = "", failedStep = OnboardingValidationStep.Tunnel)

        assertEquals(OnboardingSceneState(dns = "Resolver"), scene(failed, statesTest = false))
    }
}
