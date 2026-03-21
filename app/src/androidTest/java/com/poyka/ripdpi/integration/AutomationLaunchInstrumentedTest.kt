package com.poyka.ripdpi.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class AutomationLaunchInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule =
        createAutomationComposeRule(
            automationLaunchIntent(
                startRoute = Route.AdvancedSettings.route,
                dataPreset = com.poyka.ripdpi.automation.AutomationDataPreset.SettingsReady,
            ),
        )

    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun launchContractSeedsSettingsAndOpensRequestedRoute() =
        runBlocking {
            composeRule.waitForTag(RipDpiTestTags.screen(Route.AdvancedSettings))
            composeRule.onNodeWithTag(RipDpiTestTags.screen(Route.AdvancedSettings)).assertIsDisplayed()

            val settings = appSettingsRepository.snapshot()
            assertTrue(settings.onboardingComplete)
            assertFalse(settings.biometricEnabled)
            assertTrue(settings.webrtcProtectionEnabled)
            assertEquals("cloudflare", settings.dnsProviderId)
            assertEquals(AppStatus.Halted to Mode.VPN, serviceStateStore.status.value)
        }
}
