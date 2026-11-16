package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingSignalLineTest {
    private fun modeOnTestStep(state: OnboardingValidationState) = onboardingSignal(state, onTestStep = true).mode

    @Test
    fun `the line is flat until the test starts`() {
        assertEquals(RipDpiSignalMode.Idle, modeOnTestStep(OnboardingValidationState.Idle))
    }

    @Test
    fun `every busy state seeks a path, including the permission requests`() {
        listOf(
            OnboardingValidationState.RequestingNotifications,
            OnboardingValidationState.RequestingVpnConsent,
            OnboardingValidationState.StartingMode(Mode.VPN),
            OnboardingValidationState.CheckingDns(Mode.VPN),
            OnboardingValidationState.RunningTrafficCheck(Mode.Proxy),
        ).forEach { state ->
            assertEquals(state.toString(), RipDpiSignalMode.Seeking, modeOnTestStep(state))
        }
    }

    @Test
    fun `a passed test shows a live path`() {
        val passed = OnboardingValidationState.Success(mode = Mode.VPN, dnsSummary = "System DNS")

        assertEquals(RipDpiSignalMode.Live, modeOnTestStep(passed))
    }

    @Test
    fun `a failed test breaks the line at its stage, in checklist order`() {
        fun failedAt(step: OnboardingValidationStep?) =
            onboardingSignal(OnboardingValidationState.Failed(reason = "", failedStep = step), onTestStep = true)

        assertEquals(
            OnboardingSignal(RipDpiSignalMode.Broken, breakAt = 0.25f),
            failedAt(OnboardingValidationStep.Tunnel),
        )
        assertEquals(OnboardingSignal(RipDpiSignalMode.Broken, breakAt = 0.5f), failedAt(OnboardingValidationStep.Dns))
        assertEquals(
            OnboardingSignal(RipDpiSignalMode.Broken, breakAt = 0.75f),
            failedAt(OnboardingValidationStep.Connectivity),
        )
        assertEquals(OnboardingSignal(RipDpiSignalMode.Broken, breakAt = 0.5f), failedAt(null))
    }

    @Test
    fun `a failed test does not mark an earlier step as broken`() {
        val failed = OnboardingValidationState.Failed(reason = "", failedStep = OnboardingValidationStep.Dns)

        assertEquals(RipDpiSignalMode.Idle, onboardingSignal(failed, onTestStep = false).mode)
    }
}
