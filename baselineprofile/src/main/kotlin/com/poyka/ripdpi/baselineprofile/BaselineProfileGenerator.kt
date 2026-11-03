package com.poyka.ripdpi.baselineprofile

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
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

            // The first launch shows onboarding; later iterations start on Home.
            device.findObject(By.res("onboarding-skip"))?.click()
            reach("home-screen")

            // Bottom tabs: Diagnostics, then Settings.
            tap("bottom-nav-diagnostics")
            reach("diagnostics-screen")
            tap("bottom-nav-settings")
            reach("settings-screen")

            // Push screens from Settings and come back: the NavHost transitions, tonal cards and empty states.
            openSettingsRow("settings-detection-check", "detection_check-screen")

            tap("bottom-nav-home")
            reach("home-screen")
        }
    }

    /** Opens a Settings row by its test tag, scrolling the list to it, and returns to Settings. */
    private fun MacrobenchmarkScope.openSettingsRow(
        rowTag: String,
        screenTag: String,
    ) {
        val x = device.displayWidth / 2
        val height = device.displayHeight
        // Back to the top, then down in half-screen steps until the row sits clear of the bottom navigation.
        repeat(MAX_SCROLLS) { device.swipe(x, height / 4, x, height * 3 / 4, SWIPE_STEPS) }
        device.waitForIdle()
        var row = device.findObject(By.res(rowTag))
        var scrolls = 0
        while ((row == null || row.visibleCenter.y > height * 3 / 5) && scrolls < MAX_SCROLLS) {
            device.swipe(x, height * 7 / 10, x, height * 3 / 10, SWIPE_STEPS)
            device.waitForIdle()
            row = device.findObject(By.res(rowTag))
            scrolls++
        }
        assertNotNull("Settings row '$rowTag' not found", row)
        // A fling is still settling when the swipe returns, and a click on a moving list misses.
        Thread.sleep(SETTLE_MS)
        device.findObject(By.res(rowTag)).click()
        reach(screenTag)
        device.pressBack()
        reach("settings-screen")
    }

    private fun MacrobenchmarkScope.tap(resourceId: String) {
        val target = device.findObject(By.res(resourceId))
        assertNotNull("Missing $resourceId", target)
        target.click()
    }

    /** Waits for a screen tag and logs it, so the journey log shows every step that was reached. */
    private fun MacrobenchmarkScope.reach(screenTag: String) {
        val reached = device.wait(Until.hasObject(By.res(screenTag)), NAVIGATION_TIMEOUT_MS)
        Log.i(TAG, "reached=$reached screen=$screenTag")
        if (!reached) {
            val onScreen =
                device.findObjects(By.pkg("com.poyka.ripdpi")).map {
                    "${it.resourceName}|${it.text}|${it.contentDescription}"
                }
            Log.i(TAG, "on screen instead: $onScreen")
        }
        check(reached) { "Journey stopped: $screenTag was not reached" }
        device.waitForIdle()
    }

    private companion object {
        const val TAG = "BaselineJourney"
        const val NAVIGATION_TIMEOUT_MS = 5_000L
        const val MAX_SCROLLS = 12
        const val SWIPE_STEPS = 20
        const val SETTLE_MS = 700L
    }
}
