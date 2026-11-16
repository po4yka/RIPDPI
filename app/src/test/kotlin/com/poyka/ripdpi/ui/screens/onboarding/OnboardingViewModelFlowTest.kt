package com.poyka.ripdpi.ui.screens.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The steps around the four original ones: saved step, completion, DNS choice, and guidance actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelFlowTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `notification explanation is off when the permission is granted`() =
        runTest {
            assertFalse(createOnboardingViewModel().uiState.value.notificationsNoteVisible)
        }

    @Test
    fun `current step and the completion return step survive process death`() =
        runTest {
            val savedStateHandle = SavedStateHandle()
            val vm = createOnboardingViewModel(savedStateHandle = savedStateHandle)
            vm.setCurrentPage(3)
            vm.setCurrentPage(OnboardingCompletionPageIndex)

            val restored = createOnboardingViewModel(savedStateHandle = savedStateHandle)

            assertEquals(OnboardingCompletionPageIndex, restored.uiState.value.currentPage)
            assertEquals(OnboardingValidationState.Idle, restored.uiState.value.validationState)
            restored.previousPage()
            assertEquals(3, restored.uiState.value.currentPage)
            assertEquals(3, createOnboardingViewModel(savedStateHandle = savedStateHandle).uiState.value.currentPage)
        }

    @Test
    fun `previousPage goes one step back`() =
        runTest {
            val vm = createOnboardingViewModel()
            vm.setCurrentPage(4)

            vm.previousPage()

            assertEquals(3, vm.uiState.value.currentPage)
        }

    @Test
    fun `skip exits show the completion step without completing onboarding`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val vm = createOnboardingViewModel(repository = repository)

            vm.effects.test {
                vm.setCurrentPage(OnboardingCompletionPageIndex)
                assertEquals(OnboardingCompletionPageIndex, vm.uiState.value.currentPage)
                expectNoEvents()
                assertFalse(repository.snapshot().onboardingComplete)

                vm.finish()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }
            assertTrue(repository.snapshot().onboardingComplete)
        }

    @Test
    fun `a passed test shows the completion step and keeps the tunnel for Done`() =
        runTest {
            val runner = FakeOnboardingModeValidationRunner()
            val vm = createOnboardingViewModel(validationRunner = runner)
            vm.setCurrentPage(OnboardingTestPageIndex)

            vm.runValidation()
            advanceUntilIdle()

            assertEquals(OnboardingCompletionPageIndex, vm.uiState.value.currentPage)
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Success)
            // The tunnel runs, so Back must not return to a choice.
            vm.previousPage()
            assertEquals(OnboardingCompletionPageIndex, vm.uiState.value.currentPage)
            assertEquals(0, runner.stopCount)
        }

    @Test
    fun `finishing without testing after a failure returns to the test step with Back`() =
        runTest {
            val runner =
                FakeOnboardingModeValidationRunner().apply {
                    result = OnboardingValidationResult.Failed(reason = "No answer")
                }
            val vm = createOnboardingViewModel(validationRunner = runner)
            vm.setCurrentPage(OnboardingTestPageIndex)
            vm.runValidation()
            advanceUntilIdle()
            assertEquals(OnboardingTestPageIndex, vm.uiState.value.currentPage)

            vm.setCurrentPage(OnboardingCompletionPageIndex)
            assertEquals(OnboardingCompletionPageIndex, vm.uiState.value.currentPage)
            assertTrue(vm.uiState.value.validationState is OnboardingValidationState.Failed)

            vm.previousPage()
            assertEquals(OnboardingTestPageIndex, vm.uiState.value.currentPage)
        }

    @Test
    fun `selecting a dns resolver persists its encrypted settings and resets the test`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val vm = createOnboardingViewModel(repository = repository)
            vm.runValidation()
            advanceUntilIdle()

            vm.selectDnsProvider(DnsProviderGoogle)
            advanceUntilIdle()

            val settings = repository.snapshot()
            assertEquals(DnsProviderGoogle, settings.dnsProviderId)
            assertEquals(DnsModeEncrypted, settings.dnsMode)
            assertEquals("dns.google", settings.encryptedDnsHost)
            assertEquals(DnsProviderGoogle, vm.uiState.value.selectedDnsProviderId)
            assertEquals(OnboardingValidationState.Idle, vm.uiState.value.validationState)

            vm.selectDnsProvider(OnboardingDnsSystemId)
            advanceUntilIdle()

            assertEquals(
                com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue.encryptedDnsHost,
                repository.snapshot().encryptedDnsHost,
            )
            assertEquals(OnboardingDnsSystemId, vm.uiState.value.selectedDnsProviderId)
        }

    @Test
    fun `guidance actions open the battery and vpn settings without a permission request`() =
        runTest {
            val vm = createOnboardingViewModel()

            vm.effects.test {
                vm.openSystemSettings(vpn = false)
                val battery = awaitItem() as OnboardingEffect.OpenSystemSettings
                vm.openSystemSettings(vpn = true)
                val vpn = awaitItem() as OnboardingEffect.OpenSystemSettings
                assertTrue(battery.intent != vpn.intent)
            }
            assertEquals(OnboardingValidationState.Idle, vm.uiState.value.validationState)
        }
}
