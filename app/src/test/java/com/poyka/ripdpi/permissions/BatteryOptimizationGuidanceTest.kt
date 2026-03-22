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
    fun `returns manufacturer-neutral doze copy`() {
        assertEquals(
            R.string.permissions_battery_body,
            BatteryOptimizationGuidance.dozeIssueMessageRes(),
        )
        assertEquals(
            R.string.settings_permissions_battery_ready,
            BatteryOptimizationGuidance.dozeReadySubtitleRes(),
        )
        assertEquals(
            R.string.settings_permissions_battery_needed,
            BatteryOptimizationGuidance.dozeRecommendedSubtitleRes(),
        )
    }

    @Test
    fun `returns samsung-specific background guidance for samsung devices`() {
        assertEquals(
            R.string.permissions_background_activity_title,
            BatteryOptimizationGuidance.backgroundGuidanceTitleRes(),
        )
        assertEquals(
            R.string.permissions_background_activity_body_samsung,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("samsung"),
        )
    }

    @Test
    fun `returns generic background guidance for non samsung devices`() {
        assertEquals(
            R.string.permissions_background_activity_body,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Google"),
        )
    }
}
