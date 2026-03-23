package com.poyka.ripdpi.integration

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.ui.navigation.Route
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class AutomationPermissionFlowInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule =
        createAutomationComposeRule(
            automationLaunchIntent(
                startRoute = Route.Home.route,
                permissionPreset = AutomationPermissionPreset.NotificationsMissing,
            ),
        )

    @Inject
    lateinit var serviceStateStore: ServiceStateStore

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun tappingConnectAutoGrantsPermissionsAndStartsFakeService() {
        composeRule.waitForAutomationTag(RipDpiTestTags.screen(Route.Home))
        composeRule.waitForAutomationTag(RipDpiTestTags.HomePermissionIssueBanner)

        composeRule.onNodeWithTag(RipDpiTestTags.HomeConnectionButton).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            serviceStateStore.status.value == (AppStatus.Running to Mode.VPN)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(RipDpiTestTags.HomePermissionIssueBanner),
                ).fetchSemanticsNodes()
                .isEmpty()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(RipDpiTestTags.HomePermissionIssueBanner),
                ).fetchSemanticsNodes()
                .isEmpty()
        }
        assertEquals(AppStatus.Running to Mode.VPN, serviceStateStore.status.value)
    }
}
