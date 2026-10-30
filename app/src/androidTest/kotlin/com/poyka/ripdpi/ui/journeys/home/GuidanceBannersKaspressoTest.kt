package com.poyka.ripdpi.ui.journeys.home

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.ui.journeys.waitForNoTag
import com.poyka.ripdpi.ui.journeys.waitForSettings
import com.poyka.ripdpi.ui.journeys.waitForTag
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class SettingsGuidanceBannerKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun dismissingTheBackgroundGuidanceBannerPersistsAcrossNavigation() =
        run {
            with(SettingsPage(composeRule)) {
                step("Guidance banner is shown") {
                    awaitDisplayed(guidanceBanner)
                }
                step("Dismiss stores the flag and removes the banner") {
                    click(bannerDismiss)
                    composeRule.waitForSettings(
                        appSettingsRepository,
                        timeoutMillis = 5_000,
                    ) { it.backgroundGuidanceDismissed }
                    composeRule.waitForNoTag(RipDpiTestTags.SettingsBackgroundGuidanceBanner, timeoutMillis = 5_000)
                }
            }
            step("Leave and return to Settings, the banner stays dismissed") {
                composeRule.onNode(hasTestTag(RipDpiTestTags.bottomNav(Route.Home))).performClick()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
                composeRule.onNode(hasTestTag(RipDpiTestTags.bottomNav(Route.Settings))).performClick()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Settings))
                composeRule.waitForNoTag(RipDpiTestTags.SettingsBackgroundGuidanceBanner, timeoutMillis = 5_000)
            }
            step("Home keeps only the lockdown notice, the guidance notice is gone") {
                composeRule.onNode(hasTestTag(RipDpiTestTags.bottomNav(Route.Home))).performClick()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
                composeRule.waitForTag(RipDpiTestTags.HomeSetupHealthRow)
                composeRule.waitForNoTag(RipDpiTestTags.HomeAdvisoryMore, timeoutMillis = 5_000)
            }
        }
}

@HiltAndroidTest
class HomeGuidanceRowKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun reviewingTheBackgroundGuidanceRowDismissesItPersistently() =
        run {
            with(HomePage(composeRule)) {
                step("Guidance notice sits behind the lockdown notice's disclosure") {
                    node(setupHealthRow).assertIsDisplayed()
                    click(advisoryMore)
                    node(setupHealthDetails).assertIsDisplayed()
                    assertEquals(1, count(setupHealthAction))
                }
                step("Acting on it stores the dismissal and removes the disclosure") {
                    clickAdvisoryAt(0)
                    composeRule.waitForSettings(
                        appSettingsRepository,
                        timeoutMillis = 5_000,
                    ) { it.backgroundGuidanceDismissed }
                    composeRule.waitForNoTag(RipDpiTestTags.HomeAdvisoryMore, timeoutMillis = 5_000)
                }
            }
        }
}

@HiltAndroidTest
class HomeBatteryReviewKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Home.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            permissionPreset = AutomationPermissionPreset.BatteryReview,
        ),
    ) {
    @Inject
    lateinit var appSettingsRepository: AppSettingsRepository

    @Test
    fun batteryReviewRowLeadsAndActingOnItPersistsTheDismissal() =
        run {
            with(HomePage(composeRule)) {
                step("Battery and guidance notices sit behind the lockdown notice's disclosure") {
                    node(setupHealthRow).assertIsDisplayed()
                    click(advisoryMore)
                    node(setupHealthDetails).assertIsDisplayed()
                    assertEquals(2, count(setupHealthAction))
                }
                step("Acting on the battery notice (listed first) stores the banner dismissal") {
                    clickAdvisoryAt(0)
                    composeRule.waitForSettings(
                        appSettingsRepository,
                        timeoutMillis = 5_000,
                    ) { it.batteryBannerDismissed }
                }
            }
        }
}

@HiltAndroidTest
class SettingsBatteryReviewKaspressoTest :
    RipDpiKaspressoTest(
        automationLaunchIntent(
            startRoute = Route.Settings.stableRoute,
            dataPreset = AutomationDataPreset.SettingsReady,
            permissionPreset = AutomationPermissionPreset.BatteryReview,
        ),
    ) {
    @Test
    fun batteryOptimizationPermissionCardIsListed() =
        run {
            with(SettingsPage(composeRule)) {
                step("Battery optimization permission row is shown") {
                    awaitDisplayed(batteryPermission)
                }
            }
        }
}
