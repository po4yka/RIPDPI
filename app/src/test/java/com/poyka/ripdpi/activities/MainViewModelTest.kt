package com.poyka.ripdpi.activities

import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelTest {
    @Test
    fun `calculateTransferredBytes clamps negative deltas`() {
        assertEquals(0L, calculateTransferredBytes(totalBytes = 128L, baselineBytes = 256L))
    }

    @Test
    fun `calculateTransferredBytes returns positive deltas`() {
        assertEquals(512L, calculateTransferredBytes(totalBytes = 1_024L, baselineBytes = 512L))
    }

    @Test
    fun `startup destination prefers onboarding until it is completed`() {
        val settings = AppSettings.newBuilder().build()

        assertEquals(Route.Onboarding.route, resolveStartupDestination(settings))
    }

    @Test
    fun `startup destination opens biometric gate after onboarding`() {
        val settings =
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .setBiometricEnabled(true)
                .build()

        assertEquals(Route.BiometricPrompt.route, resolveStartupDestination(settings))
    }

    @Test
    fun `startup destination opens home when onboarding is complete without biometrics`() {
        val settings =
            AppSettings
                .newBuilder()
                .setOnboardingComplete(true)
                .build()

        assertEquals(Route.Home.route, resolveStartupDestination(settings))
    }
}
