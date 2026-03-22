package com.poyka.ripdpi.permissions

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryOptimizationIntentsTest {
    @Test
    fun `uses battery optimization settings when review activity is available`() {
        val route =
            BatteryOptimizationIntents.resolveRoute(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ) { action ->
                action == android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            }

        assertEquals(BatteryOptimizationRoute.SettingsList, route)
    }

    @Test
    fun `falls back to app details when no battery optimization activity is available`() {
        val route =
            BatteryOptimizationIntents.resolveRoute(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ) { false }

        assertEquals(BatteryOptimizationRoute.AppDetails, route)
    }
}
