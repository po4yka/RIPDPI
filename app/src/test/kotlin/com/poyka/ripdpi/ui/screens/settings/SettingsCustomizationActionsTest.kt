package com.poyka.ripdpi.ui.screens.settings

import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeLauncherIconController
import com.poyka.ripdpi.activities.FakePinVerifier
import com.poyka.ripdpi.platform.StartOnBootController
import com.poyka.ripdpi.proto.VpnEngineFailureAction
import com.poyka.ripdpi.security.PinLockoutManager
import com.poyka.ripdpi.settings.state.toUiState
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
    @Test
    fun `haptic choice persists both values without changing boot behavior`() =
        runTest {
            val harness = buildHarness()
            harness.actions.setHapticFeedbackDisabled(true)
            advanceUntilIdle()
            assertTrue(harness.repository.snapshot().hapticFeedbackDisabled)

            harness.actions.setHapticFeedbackDisabled(false)
            advanceUntilIdle()
            assertFalse(harness.repository.snapshot().hapticFeedbackDisabled)
            assertTrue(harness.controller.receiverEnabledCalls.isEmpty())
        }

    @Test
    fun `dynamic color choice persists both values`() =
        runTest {
            val harness = buildHarness()
            harness.actions.setDynamicColorEnabled(true)
            advanceUntilIdle()
            assertTrue(harness.repository.snapshot().dynamicColorEnabled)

            harness.actions.setDynamicColorEnabled(false)
            advanceUntilIdle()
            assertFalse(harness.repository.snapshot().dynamicColorEnabled)
        }

    @Test
    fun `engine failure choice persists both values and maps to ui state`() =
        runTest {
            val harness = buildHarness()
            assertTrue(
                harness.repository
                    .snapshot()
                    .toUiState()
                    .blockTrafficOnEngineFailure,
            )

            harness.actions.setBlockTrafficOnEngineFailure(false)
            advanceUntilIdle()
            assertEquals(
                VpnEngineFailureAction.VPN_ENGINE_FAILURE_ACTION_STOP,
                harness.repository.snapshot().vpnEngineFailureAction,
            )
            assertFalse(
                harness.repository
                    .snapshot()
                    .toUiState()
                    .blockTrafficOnEngineFailure,
            )

            harness.actions.setBlockTrafficOnEngineFailure(true)
            advanceUntilIdle()
            assertTrue(
                harness.repository
                    .snapshot()
                    .toUiState()
                    .blockTrafficOnEngineFailure,
            )
        }

    @Test
    fun `acknowledging the reset notice clears the flag`() =
        runTest {
            val harness = buildHarness()
            harness.repository.update { setAppLockResetNoticePending(true) }

            harness.actions.acknowledgeAppLockResetNotice()
            advanceUntilIdle()

            assertFalse(harness.repository.snapshot().appLockResetNoticePending)
        }

    @Test
    fun `setting a new pin clears the reset notice but removing the pin keeps it`() =
        runTest {
            val harness = buildHarness()
            harness.repository.update { setAppLockResetNoticePending(true) }

            harness.actions.setBackupPin("")
            advanceUntilIdle()
            assertTrue(harness.repository.snapshot().appLockResetNoticePending)

            harness.actions.setBackupPin("1234")
            advanceUntilIdle()
            assertFalse(harness.repository.snapshot().appLockResetNoticePending)
        }

    private class RecordingStartOnBootController : StartOnBootController(ApplicationProvider.getApplicationContext()) {
        val receiverEnabledCalls = mutableListOf<Boolean>()

        override fun setReceiverEnabled(enabled: Boolean) {
            receiverEnabledCalls += enabled
        }
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

    private fun CoroutineScope.buildHarness(): Harness {
        val repository = FakeAppSettingsRepository()
        // replay buffer captures emissions without a live collector in the test.
        val effectFlow =
            MutableSharedFlow<SettingsEffect>(
                replay = 16,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val controller = RecordingStartOnBootController()
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
    fun `enabling boot persists the preference without requesting settings`() =
        runTest {
            val harness = buildHarness()

            harness.actions.setStartOnBootEnabled(true)
            advanceUntilIdle()

            assertTrue(harness.repository.snapshot().startOnBoot)
            assertEquals(listOf(true), harness.controller.receiverEnabledCalls)
            assertEquals(listOf(SettingsEffect.SettingChanged("startOnBoot", "true")), harness.emittedEffects)
        }

    @Test
    fun `disabling boot persists the preference without requesting settings`() =
        runTest {
            val harness = buildHarness()

            harness.actions.setStartOnBootEnabled(false)
            advanceUntilIdle()

            assertFalse(harness.repository.snapshot().startOnBoot)
            assertEquals(listOf(false), harness.controller.receiverEnabledCalls)
            assertEquals(listOf(SettingsEffect.SettingChanged("startOnBoot", "false")), harness.emittedEffects)
        }
}
