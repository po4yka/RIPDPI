package com.poyka.ripdpi.platform

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartOnBootBatteryIntentsTest {
    @Test
    fun `battery guidance opens settings without direct exemption request`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val actions = StartOnBootController(context).batteryOptimizationIntents().map { it.action }

        assertFalse(actions.contains(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
        val batterySettingsIndex = actions.indexOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val appDetailsIndex = actions.indexOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertTrue(batterySettingsIndex >= 0)
        assertTrue(appDetailsIndex > batterySettingsIndex)
    }
}
