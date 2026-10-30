package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.JourneyPage
import com.poyka.ripdpi.ui.journeys.JourneyRule
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject

private class HostPackDialogPage(
    rule: JourneyRule,
) : JourneyPage<HostPackDialogPage>(rule, RipDpiTestTags.HostPackApplyDialog) {
    val targetDropdown = RipDpiTestTags.HostPackTargetDropdown
    val applyModeDropdown = RipDpiTestTags.HostPackApplyModeDropdown
    val confirm = RipDpiTestTags.HostPackApplyConfirm
    val dismiss = RipDpiTestTags.HostPackApplyDismiss
}

@HiltAndroidTest
class HostPackDialogKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.AdvancedSettings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    private fun openYoutubeDialog() {
        with(AdvancedSettingsPage(composeRule)) { click(hostPackPreset("youtube")) }
        with(HostPackDialogPage(composeRule)) {
            awaitScreen()
            node(targetDropdown).assertIsDisplayed()
            node(applyModeDropdown).assertIsDisplayed()
        }
    }

    @Test
    fun confirmingAppliesTheHostPackAndClosesTheDialog() =
        run {
            step("The host lists start empty") {
                val settings = runBlocking { appSettingsRepository.snapshot() }
                assertTrue(settings.hostsBlacklist.isBlank() && settings.hostsWhitelist.isBlank())
            }
            step("Open the YouTube pack dialog and confirm") {
                openYoutubeDialog()
                with(HostPackDialogPage(composeRule)) {
                    click(confirm)
                    waitGone(screenTag)
                }
            }
            step("The pack hosts are stored") {
                composeRule.waitForSettings(appSettingsRepository) {
                    it.hostsBlacklist.isNotBlank() || it.hostsWhitelist.isNotBlank()
                }
            }
        }

    @Test
    fun dismissingClosesTheDialogWithoutChangingTheHostLists() =
        run {
            step("Open the dialog and dismiss it") {
                openYoutubeDialog()
                with(HostPackDialogPage(composeRule)) {
                    click(dismiss)
                    waitGone(screenTag)
                }
            }
            step("The host lists are still empty") {
                val settings = runBlocking { appSettingsRepository.snapshot() }
                assertTrue(settings.hostsBlacklist.isBlank() && settings.hostsWhitelist.isBlank())
            }
        }
}
