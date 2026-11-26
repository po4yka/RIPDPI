package com.poyka.ripdpi.permissions

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

internal object BatteryOptimizationIntents {
    fun createAppDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        }
}
