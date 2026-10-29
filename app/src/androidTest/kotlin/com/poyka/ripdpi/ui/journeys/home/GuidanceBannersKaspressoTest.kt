package com.poyka.ripdpi.ui.journeys.home

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.integration.RipDpiKaspressoTest
import com.poyka.ripdpi.integration.automationLaunchIntent
import com.poyka.ripdpi.integration.scrollAndClick
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Inject

private class SettingsPage(
    provider: SemanticsNodeInteractionsProvider,
) : ComposeScreen<SettingsPage>(provider, { hasTestTag(RipDpiTestTags.screen(Route.Settings)) }) {
    fun tagged(tag: String): KNode = onNode { hasTestTag(tag) }

    val guidanceBanner = tagged(RipDpiTestTags.SettingsBackgroundGuidanceBanner)
    val bannerDismiss = tagged(RipDpiTestTags.WarningBannerDismiss)
    val batteryPermission = tagged(RipDpiTestTags.settingsPermission(PermissionKind.BatteryOptimization))
}

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
            ComposeScreen.onComposeScreen<SettingsPage>(composeRule) {
                step("Guidance banner is shown") {
                    guidanceBanner.performScrollTo()
                    guidanceBanner.assertIsDisplayed()
                }
                step("Dismiss stores the flag and removes the banner") {
                    bannerDismiss.scrollAndClick()
                    composeRule.waitForSettings(appSettingsRepository) { it.backgroundGuidanceDismissed }
                    composeRule.waitForTagGone(RipDpiTestTags.SettingsBackgroundGuidanceBanner)
                }
            }
            step("Leave and return to Settings, the banner stays dismissed") {
                composeRule.onNode(hasTestTag(RipDpiTestTags.bottomNav(Route.Home))).performClick()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
                composeRule.onNode(hasTestTag(RipDpiTestTags.bottomNav(Route.Settings))).performClick()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Settings))
                composeRule.waitForTagGone(RipDpiTestTags.SettingsBackgroundGuidanceBanner)
            }
            step("Home keeps only the lockdown notice, the guidance notice is gone") {
                composeRule.onNode(hasTestTag(RipDpiTestTags.bottomNav(Route.Home))).performClick()
                composeRule.waitForTag(RipDpiTestTags.screen(Route.Home))
                composeRule.waitForTag(RipDpiTestTags.HomeSetupHealthRow)
                composeRule.waitForTagGone(RipDpiTestTags.HomeAdvisoryMore)
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
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Guidance notice sits behind the lockdown notice's disclosure") {
                    setupHealthRow.assertIsDisplayed()
                    advisoryMore.scrollAndClick()
                    setupHealthDetails.assertIsDisplayed()
                    assertEquals(1, composeRule.tagCount(RipDpiTestTags.HomeSetupHealthAction))
                }
                step("Acting on it stores the dismissal and removes the disclosure") {
                    composeRule.clickAdvisoryAt(0)
                    composeRule.waitForSettings(appSettingsRepository) { it.backgroundGuidanceDismissed }
                    composeRule.waitForTagGone(RipDpiTestTags.HomeAdvisoryMore)
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
            ComposeScreen.onComposeScreen<HomePage>(composeRule) {
                step("Battery and guidance notices sit behind the lockdown notice's disclosure") {
                    setupHealthRow.assertIsDisplayed()
                    advisoryMore.scrollAndClick()
                    setupHealthDetails.assertIsDisplayed()
                    assertEquals(2, composeRule.tagCount(RipDpiTestTags.HomeSetupHealthAction))
                }
                step("Acting on the battery notice (listed first) stores the banner dismissal") {
                    composeRule.clickAdvisoryAt(0)
                    composeRule.waitForSettings(appSettingsRepository) { it.batteryBannerDismissed }
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
            ComposeScreen.onComposeScreen<SettingsPage>(composeRule) {
                step("Battery optimization permission row is shown") {
                    batteryPermission.performScrollTo()
                    batteryPermission.assertIsDisplayed()
                }
            }
        }
}
