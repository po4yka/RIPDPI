package com.poyka.ripdpi.activities

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.platform.StartOnBootController
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsCustomizationActionsTest {
    private class RecordingStartOnBootController(
        var batteryIgnored: Boolean,
    ) : StartOnBootController(ApplicationProvider.getApplicationContext()) {
        val receiverEnabledCalls = mutableListOf<Boolean>()

        override fun setReceiverEnabled(enabled: Boolean) {
            receiverEnabledCalls += enabled
        }

        override fun isBatteryOptimizationIgnored(): Boolean = batteryIgnored
    }

    private class Harness(
        val actions: SettingsCustomizationActions,
        val controller: RecordingStartOnBootController,
        val repository: FakeAppSettingsRepository,
        val effects: MutableSharedFlow<SettingsEffect>,
    ) {
        val emittedEffects: List<SettingsEffect>
            get() = effects.replayCache
    }

    private fun CoroutineScope.buildHarness(batteryIgnored: Boolean): Harness {
        val repository = FakeAppSettingsRepository()
        // replay buffer captures emissions without a live collector in the test.
        val effectFlow =
            MutableSharedFlow<SettingsEffect>(
                replay = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val controller = RecordingStartOnBootController(batteryIgnored)
        val actions =
            SettingsCustomizationActions(
                mutations = SettingsMutationRunner(this, repository, effectFlow),
                launcherIconController = FakeLauncherIconController(),
                startOnBootController = controller,
                currentUiState = { SettingsUiState() },
                pinVerifier = FakePinVerifier(),
                pinLockoutManager = PinLockoutManager(ApplicationProvider.getApplicationContext()),
            )
        return Harness(actions, controller, repository, effectFlow)
    }

    @Test
    fun `enabling persists proto, enables receiver, and prompts when battery not ignored`() =
        runTest {
            val harness = buildHarness(batteryIgnored = false)

            harness.actions.setStartOnBootEnabled(true)
            advanceUntilIdle()

            assertTrue(harness.repository.snapshot().startOnBoot)
            assertEquals(listOf(true), harness.controller.receiverEnabledCalls)
            assertTrue(
                harness.emittedEffects.any { it is SettingsEffect.RequestDisableBatteryOptimization },
            )
        }

    @Test
    fun `enabling does not prompt when battery already ignored`() =
        runTest {
            val harness = buildHarness(batteryIgnored = true)

            harness.actions.setStartOnBootEnabled(true)
            advanceUntilIdle()

            assertTrue(harness.repository.snapshot().startOnBoot)
            assertEquals(listOf(true), harness.controller.receiverEnabledCalls)
            assertFalse(
                harness.emittedEffects.any { it is SettingsEffect.RequestDisableBatteryOptimization },
            )
        }

    @Test
    fun `disabling persists, disables receiver, and never prompts`() =
        runTest {
            val harness = buildHarness(batteryIgnored = false)

            harness.actions.setStartOnBootEnabled(false)
            advanceUntilIdle()

            assertFalse(harness.repository.snapshot().startOnBoot)
            assertEquals(listOf(false), harness.controller.receiverEnabledCalls)
            assertFalse(
                harness.emittedEffects.any { it is SettingsEffect.RequestDisableBatteryOptimization },
            )
        }
}
