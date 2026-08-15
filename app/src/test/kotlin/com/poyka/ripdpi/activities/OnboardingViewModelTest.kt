package com.poyka.ripdpi.activities

import android.content.Intent
import app.cash.turbine.test
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionResult
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingDnsSystemId
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingModeValidationRunner
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingValidationResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        validationRunner: FakeOnboardingModeValidationRunner = FakeOnboardingModeValidationRunner(),
        permissionStatusProvider: FakePermissionStatusProvider = grantedPermissionStatusProvider(),
        permissionPlatformBridge: FakePermissionPlatformBridge = FakePermissionPlatformBridge(),
    ) = OnboardingViewModel(
        settingsCoordinator = OnboardingSettingsCoordinator(repository),
        validationCoordinator = OnboardingValidationCoordinator(validationRunner),
        permissionCoordinator =
            OnboardingPermissionCoordinator(
                permissionStatusProvider = permissionStatusProvider,
                permissionPlatformBridge = permissionPlatformBridge,
                stringResolver = FakeStringResolver(),
            ),
    )

    @Test
    fun `initial state has page zero and correct total pages`() =
        runTest {
            val vm = createViewModel()
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

            val vm = createViewModel(repository = repository)
            runCurrent()

            assertEquals(Mode.Proxy, vm.uiState.value.selectedMode)
            assertEquals(DnsProviderGoogle, vm.uiState.value.selectedDnsProviderId)
        }

    @Test
    fun `setCurrentPage updates current page`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()
                vm.setCurrentPage(2)
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `setCurrentPage clamps to valid range`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.setCurrentPage(99)
                assertEquals(OnboardingPages.lastIndex, awaitItem().currentPage)

                vm.setCurrentPage(-5)
                assertEquals(0, awaitItem().currentPage)
            }
        }

    @Test
    fun `nextPage increments current page`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                awaitItem()

                vm.nextPage()
                assertEquals(1, awaitItem().currentPage)

                vm.nextPage()
                assertEquals(2, awaitItem().currentPage)
            }
        }

    @Test
    fun `nextPage does not exceed last page`() =
        runTest {
            val vm = createViewModel()
            vm.setCurrentPage(OnboardingPages.lastIndex)
            vm.uiState.test {
                assertEquals(OnboardingPages.lastIndex, awaitItem().currentPage)
                vm.nextPage()
                expectNoEvents()
            }
        }

    @Test
    fun `selectMode updates selectedMode persists draft and resets validation`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(latencyMs = 41)
                }
            val vm = createViewModel(repository = repository, validationRunner = runner)

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
    fun `selectDnsProvider updates provider persists draft and resets validation`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(latencyMs = 35)
                }
            val vm = createViewModel(repository = repository, validationRunner = runner)

            vm.runValidation()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)

            vm.selectDnsProvider(DnsProviderGoogle)
            advanceUntilIdle()

            assertEquals(DnsProviderGoogle, vm.uiState.value.selectedDnsProviderId)
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Idle)
            assertEquals(DnsProviderGoogle, repository.snapshot().dnsProviderId)
            assertEquals(1, runner.stopCount)
        }

    @Test
    fun `proxy validation success uses selected proxy mode`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(latencyMs = 22)
                }
            val vm = createViewModel(validationRunner = runner)
            vm.selectMode(Mode.Proxy)

            vm.runValidation()
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), runner.validateCalls)
            assertEquals(
                OnboardingValidationState.Success(latencyMs = 22, mode = Mode.Proxy),
                vm.uiState.value.validationState,
            )
        }

    @Test
    fun `vpn validation requests vpn consent and resumes after grant`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Success(latencyMs = 54)
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
                createViewModel(
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
                createViewModel(
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
                createViewModel(
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
            val vm = createViewModel(permissionStatusProvider = permissionStatusProvider)

            vm.effects.test {
                vm.runValidation()
                assertTrue(awaitItem() is OnboardingEffect.RequestNotificationsPermission)
            }

            assertEquals(OnboardingValidationState.RequestingNotifications, vm.uiState.value.validationState)
        }

    @Test
    fun `notifications denial surfaces permission recovery without alternate mode suggestion`() =
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
            val vm = createViewModel(permissionStatusProvider = permissionStatusProvider)

            vm.runValidation()
            advanceUntilIdle()
            vm.onNotificationPermissionResult(PermissionResult.Denied)

            val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
            assertEquals(OnboardingValidationRecoveryKind.REQUEST_NOTIFICATIONS, state.recoveryKind)
            assertEquals(null, state.suggestedMode)
        }

    @Test
    fun `permanent notifications denial recovers through settings instead of prompt`() =
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
            val settingsIntent = Intent("test.notification.settings")
            val permissionPlatformBridge = FakePermissionPlatformBridge(appSettingsIntent = settingsIntent)
            val vm =
                createViewModel(
                    permissionStatusProvider = permissionStatusProvider,
                    permissionPlatformBridge = permissionPlatformBridge,
                )

            vm.effects.test {
                vm.runValidation()
                assertTrue(awaitItem() is OnboardingEffect.RequestNotificationsPermission)
                vm.onNotificationPermissionResult(PermissionResult.DeniedPermanently)

                val state = vm.uiState.value.validationState as OnboardingValidationState.Failed
                assertEquals(OnboardingValidationRecoveryKind.REQUEST_NOTIFICATION_SETTINGS, state.recoveryKind)

                vm.runValidation()
                val settingsEffect = awaitItem() as OnboardingEffect.RequestNotificationsSettings
                assertEquals(settingsIntent, settingsEffect.intent)
            }
        }

    @Test
    fun `returning from notification settings continues when permission is granted`() =
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
            val settingsIntent = Intent("test.notification.settings")
            val vm =
                createViewModel(
                    permissionStatusProvider = permissionStatusProvider,
                    permissionPlatformBridge = FakePermissionPlatformBridge(appSettingsIntent = settingsIntent),
                )

            vm.effects.test {
                vm.runValidation()
                assertTrue(awaitItem() is OnboardingEffect.RequestNotificationsPermission)
                vm.onNotificationPermissionResult(PermissionResult.DeniedPermanently)

                vm.runValidation()
                val settingsEffect = awaitItem() as OnboardingEffect.RequestNotificationsSettings
                assertEquals(settingsIntent.action, settingsEffect.intent.action)

                permissionStatusProvider.snapshot =
                    permissionStatusProvider.snapshot.copy(notifications = PermissionStatus.Granted)
                vm.onNotificationPermissionResult(PermissionResult.ReturnedFromSettings)
                advanceUntilIdle()
                expectNoEvents()
            }

            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)
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
            val vm = createViewModel(permissionStatusProvider = permissionStatusProvider)

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
            val vm = createViewModel(validationRunner = runner)
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
            val vm = createViewModel(validationRunner = runner)

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
            val vm = createViewModel(validationRunner = runner)

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
                    result = OnboardingValidationResult.Success(latencyMs = 60)
                }
            val vm = createViewModel(repository = repository, validationRunner = runner)

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
    fun `finish disconnected stops temporary runtime before completion`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createViewModel(repository = repository, validationRunner = runner)

            vm.effects.test {
                vm.finishDisconnected()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            assertTrue(repository.snapshot().onboardingComplete)
            assertEquals(1, runner.stopCount)
        }

    @Test
    fun `finish anyway stops temporary runtime before completion`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createViewModel(repository = repository, validationRunner = runner)

            vm.effects.test {
                vm.finishAnyway()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            assertTrue(repository.snapshot().onboardingComplete)
            assertEquals(1, runner.stopCount)
        }

    @Test
    fun `skip stops temporary runtime before completion`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createViewModel(repository = repository, validationRunner = runner)

            vm.effects.test {
                vm.skip()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            assertTrue(repository.snapshot().onboardingComplete)
            assertEquals(1, runner.stopCount)
            assertEquals(vm.uiState.value.selectedMode.preferenceValue, repository.snapshot().ripdpiMode)
            assertEquals(OnboardingDnsSystemId, vm.uiState.value.selectedDnsProviderId)
            assertEquals(DnsProviderAdGuard, repository.snapshot().dnsProviderId)
        }

    private class FakeOnboardingModeValidationRunner : OnboardingModeValidationRunner {
        var result: OnboardingValidationResult = OnboardingValidationResult.Success(latencyMs = 30)
        val validateCalls = mutableListOf<Mode>()
        var stopCount: Int = 0
        var retainCount: Int = 0

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

        override fun retainActiveValidation() {
            retainCount += 1
        }
    }

    private fun grantedPermissionStatusProvider(): FakePermissionStatusProvider =
        FakePermissionStatusProvider(
            snapshot =
                PermissionSnapshot(
                    vpnConsent = PermissionStatus.Granted,
                    notifications = PermissionStatus.Granted,
                    batteryOptimization = PermissionStatus.NotApplicable,
                ),
        )
}
