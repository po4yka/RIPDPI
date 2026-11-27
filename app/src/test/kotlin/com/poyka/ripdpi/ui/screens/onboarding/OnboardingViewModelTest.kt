package com.poyka.ripdpi.ui.screens.onboarding

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakePermissionPlatformBridge
import com.poyka.ripdpi.activities.FakePermissionStatusProvider
import com.poyka.ripdpi.activities.FakeStringResolver
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.ui.screens.connection.RecordingXrayNativeProviderSelection
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial state has page zero and correct total pages`() =
        runTest {
            val vm = createOnboardingViewModel()
            vm.uiState.test {
                val state = awaitItem()
                assertEquals(0, state.currentPage)
                assertEquals(OnboardingPages.size, state.totalPages)
                assertEquals(OnboardingDnsSystemId, state.selectedDnsProviderId)
                runCurrent()
                assertEquals(OnboardingDnsSystemId, vm.uiState.value.selectedDnsProviderId)
            }
        }

    @Test
    fun `selecting proxy switches durable provider back to native`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(repository)
            val vm =
                createOnboardingViewModel(
                    repository = repository,
                    xrayNativeProviderSelection = xrayNativeProviderSelection,
                )
            runCurrent()

            vm.selectMode(Mode.Proxy)
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), xrayNativeProviderSelection.selectedModes)
        }

    @Test
    fun `proxy selection failure does not write onboarding settings`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val initialSettings = repository.snapshot()
            val failure = IllegalStateException("durable selection failed")
            val xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(repository, failure = failure)
            val coordinator = OnboardingSettingsCoordinator(repository, xrayNativeProviderSelection) { false }

            val result =
                runCatching {
                    coordinator.saveSelection(
                        mode = Mode.Proxy,
                        dnsProviderId = DnsProviderGoogle,
                        persona = "advanced",
                    )
                }

            assertEquals(failure, result.exceptionOrNull())
            assertEquals(initialSettings, repository.snapshot())
        }

    @Test
    fun `initial state restores provisional mode and dns from settings`() =
        runTest {
            val repository =
                FakeAppSettingsRepository(
                    initialSettings =
                        com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setRipdpiMode(Mode.Proxy.preferenceValue)
                            .setDnsProviderId(DnsProviderGoogle)
                            .build(),
                )

            val vm = createOnboardingViewModel(repository = repository)
            runCurrent()

            assertEquals(Mode.Proxy, vm.uiState.value.selectedMode)
            assertEquals(DnsProviderGoogle, vm.uiState.value.selectedDnsProviderId)
        }

    @Test
    fun `preset flow finishes without rewriting the preset choices`() =
        runTest {
            // A built-in provider id with a resolver address of its own, an advanced interface, and Proxy mode.
            val preset =
                com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setRipdpiMode(Mode.Proxy.preferenceValue)
                    .setDnsProviderId(DnsProviderGoogle)
                    .setDnsIp("192.0.2.53")
                    .setUiPersona("advanced")
                    .build()
            val repository = FakeAppSettingsRepository(initialSettings = preset)
            val xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(repository)
            val vm =
                createOnboardingViewModel(
                    repository = repository,
                    xrayNativeProviderSelection = xrayNativeProviderSelection,
                    presetApplied = true,
                )
            assertTrue(vm.uiState.value.presetApplied)

            // No runCurrent: the state still holds its default VPN mode, as after an immediate Skip.
            vm.finish()
            advanceUntilIdle()

            assertEquals(preset.toBuilder().setOnboardingComplete(true).build(), repository.snapshot())
            assertEquals(emptyList<Mode>(), xrayNativeProviderSelection.selectedModes)
        }

    @Test
    fun `preset flow offers no mode switch after a failed test`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val xrayNativeProviderSelection = RecordingXrayNativeProviderSelection(repository)
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Failed(reason = "No route", suggestedMode = Mode.Proxy)
                }
            val vm =
                createOnboardingViewModel(
                    repository = repository,
                    validationRunner = runner,
                    xrayNativeProviderSelection = xrayNativeProviderSelection,
                    presetApplied = true,
                )

            vm.runValidation()
            advanceUntilIdle()
            val failed = vm.uiState.value.validationState as OnboardingValidationState.Failed
            vm.acceptSuggestedMode()
            advanceUntilIdle()

            assertNull(failed.suggestedMode)
            assertEquals(Mode.VPN, vm.uiState.value.selectedMode)
            assertEquals(emptyList<Mode>(), xrayNativeProviderSelection.selectedModes)
        }

    @Test
    fun `setCurrentPage updates current page`() =
        runTest {
            val vm = createOnboardingViewModel()
            vm.uiState.test {
                awaitItem()
                vm.setCurrentPage(2)
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `setCurrentPage clamps to valid range`() =
        runTest {
            val vm = createOnboardingViewModel()
            vm.uiState.test {
                awaitItem()

                vm.setCurrentPage(99)
                assertEquals(OnboardingPages.lastIndex, awaitItem().currentPage)

                vm.setCurrentPage(-5)
                assertEquals(0, awaitItem().currentPage)
            }
        }

    @Test
    fun `continue increments current page`() =
        runTest {
            val vm = createOnboardingViewModel()
            vm.uiState.test {
                awaitItem()

                vm.setCurrentPage(vm.uiState.value.currentPage + 1)
                assertEquals(1, awaitItem().currentPage)

                vm.setCurrentPage(vm.uiState.value.currentPage + 1)
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `continue does not exceed last page`() =
        runTest {
            val vm = createOnboardingViewModel()
            vm.setCurrentPage(OnboardingPages.lastIndex)
            vm.uiState.test {
                assertEquals(OnboardingPages.lastIndex, awaitItem().currentPage)
                vm.setCurrentPage(vm.uiState.value.currentPage + 1)
                expectNoEvents()
            }
        }

    @Test
    fun `selectMode updates selectedMode persists draft and resets validation`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(dnsSummary = "Test DNS", volumeTested = true)
                }
            val vm = createOnboardingViewModel(repository = repository, validationRunner = runner)

            vm.runValidation()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)

            vm.selectMode(Mode.Proxy)
            advanceUntilIdle()

            assertEquals(Mode.Proxy, vm.uiState.value.selectedMode)
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Idle)
            assertEquals(Mode.Proxy.preferenceValue, repository.snapshot().ripdpiMode)
            assertEquals(1, runner.stopCount)
        }

    @Test
    fun `proxy validation success uses selected proxy mode`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(dnsSummary = "Test DNS", volumeTested = true)
                }
            val vm = createOnboardingViewModel(validationRunner = runner)
            vm.selectMode(Mode.Proxy)

            vm.runValidation()
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), runner.validateCalls)
            assertEquals(
                OnboardingValidationState.Success(mode = Mode.Proxy, dnsSummary = "Test DNS", volumeTested = true),
                vm.uiState.value.validationState,
            )
        }

    @Test
    fun `vpn validation requests vpn consent and resumes after grant`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(dnsSummary = "Test DNS", volumeTested = true)
                }
            val permissionStatusProvider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.NotApplicable,
                        ),
                )
            val vm =
                createOnboardingViewModel(
                    validationRunner = runner,
                    permissionStatusProvider = permissionStatusProvider,
                )

            vm.effects.test {
                vm.runValidation()
                assertTrue(awaitItem() is OnboardingEffect.RequestVpnConsent)

                permissionStatusProvider.snapshot =
                    permissionStatusProvider.snapshot.copy(vpnConsent = PermissionStatus.Granted)
                vm.onVpnPermissionResult(PermissionResult.Granted)
                advanceUntilIdle()
            }

            assertEquals(listOf(Mode.VPN), runner.validateCalls)
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)
        }

    @Test
    fun `service vpn consent rejection requests the dialog even when the pre-check reported granted`() =
        runTest {
            // Advisory pre-check reports consent granted (VpnService.prepare() can spuriously return
            // null), so nextValidationPrompt does NOT pre-empt with a dialog...
            val permissionStatusProvider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.NotApplicable,
                        ),
                )
            // ...but the service authoritatively rejects the start for missing consent.
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result =
                        OnboardingValidationResult.Failed(
                            reason = "VPN consent not given",
                            recoveryKind = OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION,
                            suggestedMode = Mode.Proxy,
                            failedStep = OnboardingValidationStep.Tunnel,
                        )
                }
            val vm =
                createOnboardingViewModel(
                    validationRunner = runner,
                    permissionStatusProvider = permissionStatusProvider,
                    permissionPlatformBridge = FakePermissionPlatformBridge(vpnPermissionIntent = Intent("fake.vpn")),
                )

            vm.effects.test {
                vm.runValidation()
                // The consent dialog is launched off the authoritative rejection, not skipped.
                assertTrue(awaitItem() is OnboardingEffect.RequestVpnConsent)
            }
            assertEquals(OnboardingValidationState.RequestingVpnConsent, vm.uiState.value.validationState)
        }

    @Test
    fun `vpn consent re-rejection after grant surfaces failure without re-looping the dialog`() =
        runTest {
            val permissionStatusProvider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.NotApplicable,
                        ),
                )
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result =
                        OnboardingValidationResult.Failed(
                            reason = "VPN consent not given",
                            recoveryKind = OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION,
                            suggestedMode = Mode.Proxy,
                            failedStep = OnboardingValidationStep.Tunnel,
                        )
                }
            val vm =
                createOnboardingViewModel(
                    validationRunner = runner,
                    permissionStatusProvider = permissionStatusProvider,
                )

            vm.effects.test {
                vm.runValidation()
                assertTrue(awaitItem() is OnboardingEffect.RequestVpnConsent)
                // User grants, but the service STILL rejects — must not re-launch the dialog forever.
                vm.onVpnPermissionResult(PermissionResult.Granted)
                advanceUntilIdle()
                expectNoEvents()
            }

            val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
            assertEquals(OnboardingValidationRecoveryKind.REQUEST_VPN_PERMISSION, state.recoveryKind)
        }

    @Test
    fun `notifications permission is requested when validation start needs it`() =
        runTest {
            val permissionStatusProvider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.RequiresSystemPrompt,
                            batteryOptimization = PermissionStatus.NotApplicable,
                        ),
                )
            val vm = createOnboardingViewModel(permissionStatusProvider = permissionStatusProvider)

            vm.effects.test {
                // The explanation is in the state from the start, before any request can leave the view model.
                assertTrue(vm.uiState.value.notificationsNoteVisible)
                expectNoEvents()

                vm.runValidation()
                assertTrue(awaitItem() is OnboardingEffect.RequestNotificationsPermission)
            }

            assertEquals(OnboardingValidationState.RequestingNotifications, vm.uiState.value.validationState)
        }

    @Test
    fun `finishing optional notification prompt continues the connection test`() =
        runTest {
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.RequiresSystemPrompt,
                        ),
                )
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createOnboardingViewModel(permissionStatusProvider = provider, validationRunner = runner)

            vm.effects.test {
                vm.runValidation()
                assertEquals(OnboardingEffect.RequestNotificationsPermission, awaitItem())
                vm.onNotificationPermissionResult()
                advanceUntilIdle()

                assertEquals(listOf(Mode.VPN), runner.validateCalls)
                assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)
                assertFalse(vm.uiState.value.notificationsNoteVisible)
                expectNoEvents()
            }
        }

    @Test
    fun `blocked notifications never send a connection test to settings`() =
        runTest {
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.Granted,
                            notifications = PermissionStatus.RequiresSettings,
                        ),
                )
            val vm = createOnboardingViewModel(permissionStatusProvider = provider)

            vm.effects.test {
                assertFalse(vm.uiState.value.notificationsNoteVisible)
                vm.runValidation()
                advanceUntilIdle()

                assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)
                expectNoEvents()
            }
        }

    @Test
    fun `VPN consent is still required after optional notification prompt closes`() =
        runTest {
            val provider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.RequiresSystemPrompt,
                        ),
                )
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createOnboardingViewModel(permissionStatusProvider = provider, validationRunner = runner)

            vm.effects.test {
                vm.runValidation()
                assertEquals(OnboardingEffect.RequestNotificationsPermission, awaitItem())
                vm.onNotificationPermissionResult()

                assertTrue(awaitItem() is OnboardingEffect.RequestVpnConsent)
                assertTrue(runner.validateCalls.isEmpty())
            }
        }

    @Test
    fun `vpn permission denial suggests switching to proxy`() =
        runTest {
            val permissionStatusProvider =
                FakePermissionStatusProvider(
                    snapshot =
                        PermissionSnapshot(
                            vpnConsent = PermissionStatus.RequiresSystemPrompt,
                            notifications = PermissionStatus.Granted,
                            batteryOptimization = PermissionStatus.NotApplicable,
                        ),
                )
            val vm = createOnboardingViewModel(permissionStatusProvider = permissionStatusProvider)

            vm.runValidation()
            advanceUntilIdle()
            vm.onVpnPermissionResult(PermissionResult.Denied)

            val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
            assertEquals(Mode.Proxy, state.suggestedMode)
        }

    @Test
    fun `proxy validation failure suggests vpn`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result =
                        OnboardingValidationResult.Failed(reason = "SOCKS listener failed", suggestedMode = Mode.VPN)
                }
            val vm = createOnboardingViewModel(validationRunner = runner)
            vm.selectMode(Mode.Proxy)

            vm.runValidation()
            advanceUntilIdle()

            val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
            assertEquals(Mode.VPN, state.suggestedMode)
        }

    @Test
    fun `dns step failure surfaces change dns recovery without alternate mode suggestion`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result =
                        OnboardingValidationResult.Failed(
                            reason = "DNS resolution failed",
                            suggestedMode = null,
                            failedStep = OnboardingValidationStep.Dns,
                        )
                }
            val vm = createOnboardingViewModel(validationRunner = runner)

            vm.runValidation()
            advanceUntilIdle()

            val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
            assertEquals(OnboardingValidationStep.Dns, state.failedStep)
            // A DNS failure is fixed by Change DNS, never by switching mode.
            assertNull(state.suggestedMode)
            // Without a suggested mode the RETRY recovery is NOT promoted to SWITCH_MODE.
            assertEquals(OnboardingValidationRecoveryKind.RETRY, state.recoveryKind)
        }

    @Test
    fun `tunnel step failure still suggests the alternate mode`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result =
                        OnboardingValidationResult.Failed(
                            reason = "VPN tunnel start timed out",
                            suggestedMode = Mode.Proxy,
                            failedStep = OnboardingValidationStep.Tunnel,
                        )
                }
            val vm = createOnboardingViewModel(validationRunner = runner)

            vm.runValidation()
            advanceUntilIdle()

            val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
            assertEquals(OnboardingValidationStep.Tunnel, state.failedStep)
            assertEquals(Mode.Proxy, state.suggestedMode)
            // A failure with a suggested alternate mode is promoted to SWITCH_MODE recovery.
            assertEquals(OnboardingValidationRecoveryKind.SWITCH_MODE, state.recoveryKind)
        }

    @Test
    fun `finish and keep running completes onboarding without stopping runtime`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(dnsSummary = "Test DNS", volumeTested = true)
                }
            val vm = createOnboardingViewModel(repository = repository, validationRunner = runner)

            vm.runValidation()
            advanceUntilIdle()

            vm.effects.test {
                vm.finishKeepingRunning()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            assertTrue(repository.snapshot().onboardingComplete)
            assertEquals(1, runner.retainCount)
            assertEquals(0, runner.stopCount)
        }

    @Test
    fun `finish disconnected stops the validated runtime before completion`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createOnboardingViewModel(repository = repository, validationRunner = runner)

            vm.effects.test {
                vm.finishDisconnected()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            assertTrue(repository.snapshot().onboardingComplete)
            assertEquals(1, runner.disconnectCount)
        }

    @Test
    fun `finish stops temporary runtime before completion`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createOnboardingViewModel(repository = repository, validationRunner = runner)

            vm.effects.test {
                vm.finish()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            assertTrue(repository.snapshot().onboardingComplete)
            assertEquals(1, runner.stopCount)
        }
}

internal fun createOnboardingViewModel(
    repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
    validationRunner: FakeOnboardingModeValidationRunner = FakeOnboardingModeValidationRunner(),
    permissionStatusProvider: FakePermissionStatusProvider = grantedPermissionStatusProvider(),
    permissionPlatformBridge: FakePermissionPlatformBridge = FakePermissionPlatformBridge(),
    xrayNativeProviderSelection: RecordingXrayNativeProviderSelection =
        RecordingXrayNativeProviderSelection(repository),
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    presetApplied: Boolean = false,
) = OnboardingViewModel(
    savedStateHandle = savedStateHandle,
    settingsCoordinator = OnboardingSettingsCoordinator(repository, xrayNativeProviderSelection) { presetApplied },
    validationCoordinator = OnboardingValidationCoordinator(validationRunner),
    permissionCoordinator =
        OnboardingPermissionCoordinator(
            permissionStatusProvider = permissionStatusProvider,
            permissionPlatformBridge = permissionPlatformBridge,
            stringResolver = FakeStringResolver(),
        ),
)

internal class FakeOnboardingModeValidationRunner : OnboardingModeValidationRunner {
    var result: OnboardingValidationResult =
        OnboardingValidationResult.Success(
            dnsSummary = "Test DNS",
            volumeTested = true,
        )
    val validateCalls = mutableListOf<Mode>()
    var stopCount: Int = 0
    var retainCount: Int = 0
    var disconnectCount: Int = 0

    override suspend fun validate(
        mode: Mode,
        onProgress: (OnboardingValidationState) -> Unit,
    ): OnboardingValidationResult {
        validateCalls += mode
        onProgress(OnboardingValidationState.StartingMode(mode))
        onProgress(OnboardingValidationState.CheckingDns(mode))
        onProgress(OnboardingValidationState.RunningTrafficCheck(mode))
        return result
    }

    override fun stopActiveValidation() {
        stopCount += 1
    }

    override suspend fun disconnect() {
        disconnectCount += 1
    }

    override fun retainActiveValidation() {
        retainCount += 1
    }
}

internal fun grantedPermissionStatusProvider(): FakePermissionStatusProvider =
    FakePermissionStatusProvider(
        snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.NotApplicable,
            ),
    )
