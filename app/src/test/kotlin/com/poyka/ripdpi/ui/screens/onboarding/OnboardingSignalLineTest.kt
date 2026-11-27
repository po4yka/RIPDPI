package com.poyka.ripdpi.ui.screens.onboarding

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.ui.components.RipDpiHapticFeedback
import com.poyka.ripdpi.ui.components.signal.RipDpiSignalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSignalLineTest {
    private fun signal(
        state: OnboardingValidationState = OnboardingValidationState.Idle,
        onTestStep: Boolean = true,
        persona: OnboardingPersona = OnboardingPersona.Guided,
        scope: Mode = Mode.VPN,
    ) = onboardingSignal(state, onTestStep, persona, scope)

    private fun modeOnTestStep(state: OnboardingValidationState) = signal(state).mode

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
        val passed = OnboardingValidationState.Success(mode = Mode.VPN, dnsSummary = "System DNS", volumeTested = true)

        assertEquals(RipDpiSignalMode.Live, modeOnTestStep(passed))
    }

    @Test
    fun `a failed test breaks the line at its stage, in checklist order`() {
        fun failedAt(step: OnboardingValidationStep?) =
            signal(OnboardingValidationState.Failed(reason = "", failedStep = step))

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

        assertEquals(RipDpiSignalMode.Idle, signal(failed, onTestStep = false).mode)
    }

    @Test
    fun `the persona sets how fine the ripple is`() {
        val guided = signal(persona = OnboardingPersona.Guided)
        val advanced = signal(persona = OnboardingPersona.Advanced)

        assertEquals(2f, guided.rippleCycles)
        assertEquals(0.12f, guided.rippleAmplitude)
        assertEquals(7f, advanced.rippleCycles)
        assertEquals(0.08f, advanced.rippleAmplitude)
    }

    @Test
    fun `the scope sets how much of the line the ripple covers`() {
        assertFalse(signal(scope = Mode.VPN).rippleOneSided)
        assertTrue(signal(scope = Mode.Proxy).rippleOneSided)
    }

    @Test
    fun `the choices keep their ripple in every test state, and the mode stays neutral before the test`() {
        val idle = signal(persona = OnboardingPersona.Advanced, scope = Mode.Proxy, onTestStep = false)
        assertEquals(RipDpiSignalMode.Idle, idle.mode)

        listOf(
            OnboardingValidationState.CheckingDns(Mode.Proxy),
            OnboardingValidationState.Success(mode = Mode.Proxy, dnsSummary = "", volumeTested = true),
            OnboardingValidationState.Failed(reason = ""),
        ).forEach { state ->
            val tested = signal(state, persona = OnboardingPersona.Advanced, scope = Mode.Proxy)
            assertEquals(state.toString(), idle.copy(mode = tested.mode, breakAt = tested.breakAt), tested)
        }
    }

    @Test
    fun `the ripple grows onto the persona step and is flat on the first and the test step`() {
        fun strength(position: Float) = onboardingRippleStrength(position, personaStep = 1, testStep = 3)

        assertEquals(0f, strength(0f))
        assertEquals(0.5f, strength(0.5f))
        assertEquals(1f, strength(1f))
        assertEquals(1f, strength(2f))
        assertEquals(0.25f, strength(2.75f))
        assertEquals(0f, strength(3f))
    }

    @Test
    fun `steps added between the choices and the test keep the ripple`() {
        assertEquals(1f, onboardingRippleStrength(position = 4f, personaStep = 2, testStep = 6))
        assertEquals(0f, onboardingRippleStrength(position = 6f, personaStep = 2, testStep = 6))
    }

    @Test
    fun `a flow with no persona step has no ripple`() {
        assertEquals(0f, onboardingRippleStrength(position = 1f, personaStep = -1, testStep = 3))
    }

    @Test
    fun `the scope shapes the ripple only from the drag onto the scope step`() {
        assertEquals(0f, onboardingScopeWeight(position = 1f, scopeStep = 2))
        assertEquals(0.5f, onboardingScopeWeight(position = 1.5f, scopeStep = 2))
        assertEquals(1f, onboardingScopeWeight(position = 3f, scopeStep = 2))
        assertEquals(0f, onboardingScopeWeight(position = 3f, scopeStep = -1))
    }

    @Test
    fun `the line states the test on the test step, and a passed test on a step after it`() {
        val passed = OnboardingValidationState.Success(mode = Mode.VPN, dnsSummary = "", volumeTested = true)
        val failed = OnboardingValidationState.Failed(reason = "")

        assertTrue(onboardingLineStatesTest(step = 3, testStep = 3, failed))
        assertTrue(onboardingLineStatesTest(step = 4, testStep = 3, passed))
        assertFalse(onboardingLineStatesTest(step = 4, testStep = 3, failed))
        assertFalse(onboardingLineStatesTest(step = 2, testStep = 3, passed))
    }

    @Test
    fun `only a finished test gives a haptic, success or error`() {
        assertEquals(
            RipDpiHapticFeedback.Success,
            onboardingResultHaptic(
                OnboardingValidationState.Success(
                    mode = Mode.VPN,
                    dnsSummary = "",
                    volumeTested = true,
                ),
            ),
        )
        assertEquals(RipDpiHapticFeedback.Error, onboardingResultHaptic(OnboardingValidationState.Failed(reason = "")))
        assertNull(onboardingResultHaptic(OnboardingValidationState.Idle))
        assertNull(onboardingResultHaptic(OnboardingValidationState.CheckingDns(Mode.VPN)))
    }
}
