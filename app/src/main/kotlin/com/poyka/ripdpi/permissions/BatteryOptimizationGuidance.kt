package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.R

internal object BatteryOptimizationGuidance {
    fun dozeIssueMessageRes(): Int = R.string.permissions_battery_body

    fun dozeReadySubtitleRes(): Int = R.string.settings_permissions_battery_ready

    fun dozeRecommendedSubtitleRes(): Int = R.string.settings_permissions_battery_needed

    fun backgroundGuidanceTitleRes(): Int = R.string.permissions_background_activity_title

    fun backgroundGuidanceMessageRes(manufacturer: String): Int {
        val normalized = manufacturer.trim().lowercase()
        return when {
            normalized == "samsung" ->
                R.string.permissions_background_activity_body_samsung
            normalized in XIAOMI_BRANDS ->
                R.string.permissions_background_activity_body_xiaomi
            normalized in HUAWEI_BRANDS ->
                R.string.permissions_background_activity_body_huawei
            normalized == "oneplus" ->
                R.string.permissions_background_activity_body_oneplus
            else ->
                R.string.permissions_background_activity_body
        }
    }

    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    private val HUAWEI_BRANDS = setOf("huawei", "honor")
}
