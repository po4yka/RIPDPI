package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationGuidanceTest {
    @Test
    fun `detects samsung manufacturers case insensitively`() {
        assertTrue(BatteryOptimizationGuidance.isSamsung("Samsung"))
        assertTrue(BatteryOptimizationGuidance.isSamsung(" samsung "))
        assertFalse(BatteryOptimizationGuidance.isSamsung("Google"))
    }

    @Test
    fun `returns samsung-specific copy for samsung devices`() {
        assertEquals(
            R.string.permissions_battery_body_samsung,
            BatteryOptimizationGuidance.issueMessageRes("samsung"),
        )
        assertEquals(
            R.string.settings_permissions_battery_ready_samsung,
            BatteryOptimizationGuidance.readySubtitleRes("samsung"),
        )
        assertEquals(
            R.string.settings_permissions_battery_needed_samsung,
            BatteryOptimizationGuidance.recommendedSubtitleRes("samsung"),
        )
        assertEquals(
            R.string.diagnostics_warn_samsung_background_limits,
            BatteryOptimizationGuidance.diagnosticsWarningRes("samsung"),
        )
    }

    @Test
    fun `returns default copy for non samsung devices`() {
        assertEquals(
            R.string.permissions_battery_body,
            BatteryOptimizationGuidance.issueMessageRes("Google"),
        )
        assertEquals(
            R.string.settings_permissions_battery_ready,
            BatteryOptimizationGuidance.readySubtitleRes("Google"),
        )
        assertEquals(
            R.string.settings_permissions_battery_needed,
            BatteryOptimizationGuidance.recommendedSubtitleRes("Google"),
        )
        assertEquals(null, BatteryOptimizationGuidance.diagnosticsWarningRes("Google"))
    }
}
