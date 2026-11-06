package com.poyka.ripdpi.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.screens.status.StatusMode
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
                startRoute = Route.Status.stableRoute,
                permissionPreset = AutomationPermissionPreset.Granted,
                dataPreset = AutomationDataPreset.SettingsReady,
            ),
        )

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun grantedPermissionLaunchShowsReadyHomeConnectionAction() {
        composeRule.waitForAutomationTag(RipDpiTestTags.screen(Route.Status))
        composeRule.waitForAutomationTag(RipDpiTestTags.ConnectionActuatorStrategyPanel)
        composeRule.waitForAutomationTag(RipDpiTestTags.StatusModesDiagnosticsCollapsed)

        composeRule
            .onNodeWithTag(RipDpiTestTags.StatusModesDiagnosticsHeader)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForAutomationTag(RipDpiTestTags.StatusModesDiagnosticsExpanded)
        composeRule.waitForAutomationTag(RipDpiTestTags.statusModeCard(StatusMode.LocalDpiBypass.name))
        composeRule
            .onNodeWithTag(RipDpiTestTags.statusModeCard(StatusMode.LocalDpiBypass.name))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
