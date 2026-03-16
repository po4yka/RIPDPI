package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.R

internal object BatteryOptimizationGuidance {
    fun issueMessageRes(manufacturer: String): Int =
        if (isSamsung(manufacturer)) {
            R.string.permissions_battery_body_samsung
        } else {
            R.string.permissions_battery_body
        }

    fun readySubtitleRes(manufacturer: String): Int =
        if (isSamsung(manufacturer)) {
            R.string.settings_permissions_battery_ready_samsung
        } else {
            R.string.settings_permissions_battery_ready
        }

    fun recommendedSubtitleRes(manufacturer: String): Int =
        if (isSamsung(manufacturer)) {
            R.string.settings_permissions_battery_needed_samsung
        } else {
            R.string.settings_permissions_battery_needed
        }

    fun diagnosticsWarningRes(manufacturer: String): Int? =
        if (isSamsung(manufacturer)) {
            R.string.diagnostics_warn_samsung_background_limits
        } else {
            null
        }

    fun isSamsung(manufacturer: String): Boolean = manufacturer.trim().equals("samsung", ignoreCase = true)
}
