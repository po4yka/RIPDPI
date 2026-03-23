package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryOptimizationGuidanceTest {
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
    fun `returns samsung guidance for samsung devices`() {
        assertEquals(
            R.string.permissions_background_activity_body_samsung,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("samsung"),
        )
        assertEquals(
            R.string.permissions_background_activity_body_samsung,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Samsung"),
        )
        assertEquals(
            R.string.permissions_background_activity_body_samsung,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes(" samsung "),
        )
    }

    @Test
    fun `returns xiaomi guidance for xiaomi brand family`() {
        assertEquals(
            R.string.permissions_background_activity_body_xiaomi,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Xiaomi"),
        )
        assertEquals(
            R.string.permissions_background_activity_body_xiaomi,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Redmi"),
        )
        assertEquals(
            R.string.permissions_background_activity_body_xiaomi,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("POCO"),
        )
    }

    @Test
    fun `returns huawei guidance for huawei and honor`() {
        assertEquals(
            R.string.permissions_background_activity_body_huawei,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("HUAWEI"),
        )
        assertEquals(
            R.string.permissions_background_activity_body_huawei,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Honor"),
        )
    }

    @Test
    fun `returns oneplus guidance for oneplus devices`() {
        assertEquals(
            R.string.permissions_background_activity_body_oneplus,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("OnePlus"),
        )
    }

    @Test
    fun `returns generic guidance for unknown manufacturers`() {
        assertEquals(
            R.string.permissions_background_activity_body,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Google"),
        )
        assertEquals(
            R.string.permissions_background_activity_body,
            BatteryOptimizationGuidance.backgroundGuidanceMessageRes("Sony"),
        )
    }
}
