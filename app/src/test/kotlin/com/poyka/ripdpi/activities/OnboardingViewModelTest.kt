package com.poyka.ripdpi.activities

import app.cash.turbine.test
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingConnectionTestRunner
import com.poyka.ripdpi.ui.screens.onboarding.OnboardingPages
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        testRunner: OnboardingConnectionTestRunner = OnboardingConnectionTestRunner(TestOwnedTlsClientFactory()),
    ) = OnboardingViewModel(repository, testRunner)

    @Test
    fun `initial state has page zero and correct total pages`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                val state = awaitItem()
                assertEquals(0, state.currentPage)
                assertEquals(OnboardingPages.size, state.totalPages)
            }
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
    fun `previousPage decrements and clamps at zero`() =
        runTest {
            val vm = createViewModel()
            vm.setCurrentPage(1)
            vm.uiState.test {
                assertEquals(1, awaitItem().currentPage)

                vm.previousPage()
                assertEquals(0, awaitItem().currentPage)

                vm.previousPage()
                expectNoEvents()
            }
        }

    @Test
    fun `selectMode updates selectedMode in state`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                assertEquals(Mode.VPN, awaitItem().selectedMode)
                vm.selectMode(Mode.Proxy)
                assertEquals(Mode.Proxy, awaitItem().selectedMode)
            }
        }

    @Test
    fun `selectDnsProvider updates selectedDnsProviderId in state`() =
        runTest {
            val vm = createViewModel()
            vm.uiState.test {
                assertEquals(DnsProviderCloudflare, awaitItem().selectedDnsProviderId)
                vm.selectDnsProvider(DnsProviderGoogle)
                assertEquals(DnsProviderGoogle, awaitItem().selectedDnsProviderId)
            }
        }

    @Test
    fun `finish persists mode and dns provider to settings`() =
        runTest {
            val repo = FakeAppSettingsRepository()
            val vm = createViewModel(repository = repo)
            vm.selectMode(Mode.Proxy)
            vm.selectDnsProvider(DnsProviderGoogle)

            vm.effects.test {
                vm.finish()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            val settings = repo.snapshot()
            assertTrue(settings.onboardingComplete)
            assertEquals(Mode.Proxy.preferenceValue, settings.ripdpiMode)
            assertEquals(DnsProviderGoogle, settings.dnsProviderId)
        }

    @Test
    fun `skip persists defaults and emits OnboardingComplete`() =
        runTest {
            val repo = FakeAppSettingsRepository()
            val vm = createViewModel(repository = repo)

            vm.effects.test {
                vm.skip()
                assertTrue(awaitItem() is OnboardingEffect.OnboardingComplete)
            }

            val settings = repo.snapshot()
            assertTrue(settings.onboardingComplete)
            assertEquals(Mode.VPN.preferenceValue, settings.ripdpiMode)
            assertEquals(DnsProviderCloudflare, settings.dnsProviderId)
        }

    private class TestOwnedTlsClientFactory : OwnedTlsClientFactory {
        override fun currentProfile(): String = "native_default"

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .apply(configure)
                .build()
    }
}
