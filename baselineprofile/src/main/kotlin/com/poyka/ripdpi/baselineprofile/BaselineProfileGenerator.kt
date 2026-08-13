package com.poyka.ripdpi.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(packageName = "com.poyka.ripdpi") {
            pressHome()
            startAutomationActivity(startRoute = HOME_ROUTE, resetState = true)
            device.waitForIdle()
            requireObject("home-screen")

            // Navigate to Diagnostics tab to profile diagnostics composables.
            requireObject("bottom-nav-diagnostics").click()
            requireObject("diagnostics-screen")
            device.waitForIdle()

            // Navigate to Settings tab.
            requireObject("bottom-nav-settings").click()
            requireObject("settings-screen")
            device.waitForIdle()

            // Return to Home tab.
            requireObject("bottom-nav-home").click()
            requireObject("home-screen")
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.startAutomationActivity(
        startRoute: String,
        resetState: Boolean,
    ) {
        startActivityAndWait { intent ->
            intent.putExtra(AUTOMATION_ENABLED_EXTRA, true)
            intent.putExtra(AUTOMATION_RESET_STATE_EXTRA, resetState)
            intent.putExtra(AUTOMATION_START_ROUTE_EXTRA, startRoute)
            intent.putExtra(AUTOMATION_DISABLE_MOTION_EXTRA, true)
            intent.putExtra(AUTOMATION_PERMISSION_PRESET_EXTRA, "granted")
            intent.putExtra(AUTOMATION_SERVICE_PRESET_EXTRA, "idle")
            intent.putExtra(AUTOMATION_DATA_PRESET_EXTRA, "settings_ready")
        }
    }

    private fun MacrobenchmarkScope.requireObject(resourceName: String) =
        requireObject(By.res(resourceName), resourceName)

    private fun MacrobenchmarkScope.requireObject(
        selector: androidx.test.uiautomator.BySelector,
        description: String,
    ) = checkNotNull(device.wait(Until.findObject(selector), NAVIGATION_TIMEOUT_MS)) {
        "Baseline profile journey could not find $description"
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MS = 5_000L
        const val HOME_ROUTE = "home"
        const val AUTOMATION_ENABLED_EXTRA = "com.poyka.ripdpi.automation.ENABLED"
        const val AUTOMATION_RESET_STATE_EXTRA = "com.poyka.ripdpi.automation.RESET_STATE"
        const val AUTOMATION_START_ROUTE_EXTRA = "com.poyka.ripdpi.automation.START_ROUTE"
        const val AUTOMATION_DISABLE_MOTION_EXTRA = "com.poyka.ripdpi.automation.DISABLE_MOTION"
        const val AUTOMATION_PERMISSION_PRESET_EXTRA = "com.poyka.ripdpi.automation.PERMISSION_PRESET"
        const val AUTOMATION_SERVICE_PRESET_EXTRA = "com.poyka.ripdpi.automation.SERVICE_PRESET"
        const val AUTOMATION_DATA_PRESET_EXTRA = "com.poyka.ripdpi.automation.DATA_PRESET"
    }
}
