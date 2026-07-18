package com.poyka.ripdpi.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.activities.HomeMode
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class AutomationPermissionFlowInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule =
        createAutomationComposeRule(
            automationLaunchIntent(
                startRoute = Route.Home.stableRoute,
                permissionPreset = AutomationPermissionPreset.Granted,
            ),
        )

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun grantedPermissionLaunchShowsReadyHomeConnectionAction() {
        composeRule.waitForAutomationTag(RipDpiTestTags.screen(Route.Home))

        composeRule
            .onNodeWithTag(RipDpiTestTags.HomeModesDiagnosticsCollapsed)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(RipDpiTestTags.homeModeCard(HomeMode.LocalDpiBypass.name))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
