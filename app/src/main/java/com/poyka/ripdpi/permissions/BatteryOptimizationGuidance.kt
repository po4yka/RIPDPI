package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.R

internal object BatteryOptimizationGuidance {
    fun dozeIssueMessageRes(): Int = R.string.permissions_battery_body

    fun dozeReadySubtitleRes(): Int = R.string.settings_permissions_battery_ready

    fun dozeRecommendedSubtitleRes(): Int = R.string.settings_permissions_battery_needed

    fun backgroundGuidanceTitleRes(): Int = R.string.permissions_background_activity_title

    fun backgroundGuidanceMessageRes(manufacturer: String): Int =
        if (isSamsung(manufacturer)) {
            R.string.permissions_background_activity_body_samsung
        } else {
            R.string.permissions_background_activity_body
        }

    fun isSamsung(manufacturer: String): Boolean = manufacturer.trim().equals("samsung", ignoreCase = true)
}
