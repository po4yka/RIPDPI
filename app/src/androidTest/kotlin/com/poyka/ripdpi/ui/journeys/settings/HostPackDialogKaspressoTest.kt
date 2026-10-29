package com.poyka.ripdpi.ui.journeys.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject

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
        composeRule.clickTag(RipDpiTestTags.hostPackPreset("youtube"))
        composeRule.awaitTag(RipDpiTestTags.HostPackApplyDialog)
        composeRule.onNodeWithTag(RipDpiTestTags.HostPackTargetDropdown).assertIsDisplayed()
        composeRule.onNodeWithTag(RipDpiTestTags.HostPackApplyModeDropdown).assertIsDisplayed()
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
                composeRule.clickTag(RipDpiTestTags.HostPackApplyConfirm)
                composeRule.awaitNoTag(RipDpiTestTags.HostPackApplyDialog)
            }
            step("The pack hosts are stored") {
                composeRule.awaitSettings(repository = appSettingsRepository) {
                    it.hostsBlacklist.isNotBlank() || it.hostsWhitelist.isNotBlank()
                }
            }
        }

    @Test
    fun dismissingClosesTheDialogWithoutChangingTheHostLists() =
        run {
            step("Open the dialog and dismiss it") {
                openYoutubeDialog()
                composeRule.clickTag(RipDpiTestTags.HostPackApplyDismiss)
                composeRule.awaitNoTag(RipDpiTestTags.HostPackApplyDialog)
            }
            step("The host lists are still empty") {
                val settings = runBlocking { appSettingsRepository.snapshot() }
                assertTrue(settings.hostsBlacklist.isBlank() && settings.hostsWhitelist.isBlank())
            }
        }
}
