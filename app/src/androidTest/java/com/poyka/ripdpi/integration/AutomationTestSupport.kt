package com.poyka.ripdpi.integration

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.activities.MainActivity
import com.poyka.ripdpi.automation.AutomationDataPreset
import com.poyka.ripdpi.automation.AutomationLaunchContract
import com.poyka.ripdpi.automation.AutomationPermissionPreset
import com.poyka.ripdpi.automation.AutomationServicePreset

internal fun createAutomationComposeRule(
    intent: Intent,
): AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
    AndroidComposeTestRule(
        activityRule = ActivityScenarioRule(intent),
        activityProvider = { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            requireNotNull(activity) { "Activity was not available from ActivityScenarioRule" }
        },
    )

internal fun automationLaunchIntent(
    startRoute: String? = null,
    resetState: Boolean = true,
    disableMotion: Boolean = true,
    permissionPreset: AutomationPermissionPreset = AutomationPermissionPreset.Granted,
    servicePreset: AutomationServicePreset = AutomationServicePreset.Idle,
    dataPreset: AutomationDataPreset = AutomationDataPreset.CleanHome,
): Intent =
    Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
        putExtra(AutomationLaunchContract.Enabled, true)
        putExtra(AutomationLaunchContract.ResetState, resetState)
        putExtra(AutomationLaunchContract.DisableMotion, disableMotion)
        putExtra(AutomationLaunchContract.PermissionPreset, permissionPreset.wireValue)
        putExtra(AutomationLaunchContract.ServicePreset, servicePreset.wireValue)
        putExtra(AutomationLaunchContract.DataPreset, dataPreset.wireValue)
        startRoute?.let { putExtra(AutomationLaunchContract.StartRoute, it) }
    }

internal fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTag(
    tag: String,
    timeoutMillis: Long = 5_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
}
