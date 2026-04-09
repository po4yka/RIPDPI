package com.poyka.ripdpi.baselineprofile

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
            startActivityAndWait()
            device.waitForIdle()

            // Navigate to Diagnostics tab to profile diagnostics composables.
            device.findObject(By.res("bottom-nav-diagnostics"))?.click()
            device.wait(Until.hasObject(By.res("diagnostics-screen")), NAVIGATION_TIMEOUT_MS)
            device.waitForIdle()

            // Navigate to Settings tab.
            device.findObject(By.res("bottom-nav-settings"))?.click()
            device.wait(Until.hasObject(By.res("settings-screen")), NAVIGATION_TIMEOUT_MS)
            device.waitForIdle()

            // Open Detection Check screen to profile detection composables.
            device.findObject(By.res("detection_run_check"))?.click()
            device.wait(Until.hasObject(By.res("detection_check-screen")), NAVIGATION_TIMEOUT_MS)
            device.waitForIdle()

            // Return to Home tab.
            device.pressBack()
            device.waitForIdle()
            device.findObject(By.res("bottom-nav-home"))?.click()
            device.wait(Until.hasObject(By.res("home-screen")), NAVIGATION_TIMEOUT_MS)
            device.waitForIdle()
        }
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MS = 5_000L
    }
}
