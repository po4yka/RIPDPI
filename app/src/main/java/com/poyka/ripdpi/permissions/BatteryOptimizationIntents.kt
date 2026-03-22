package com.poyka.ripdpi.permissions

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal enum class BatteryOptimizationRoute {
    SettingsList,
    AppDetails,
}

internal object BatteryOptimizationIntents {
    fun create(
        packageName: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
        canHandleIntent: (Intent) -> Boolean,
    ): Intent =
        when (
            resolveRoute(sdkInt = sdkInt) { action ->
                canHandleIntent(createIntentForAction(action = action, packageName = packageName))
            }
        ) {
            BatteryOptimizationRoute.SettingsList -> {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }

            BatteryOptimizationRoute.AppDetails -> {
                createAppDetailsIntent(packageName)
            }
        }

    fun createAppDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = packageUri(packageName)
        }

    internal fun resolveRoute(
        sdkInt: Int = Build.VERSION.SDK_INT,
        canHandleAction: (String) -> Boolean,
    ): BatteryOptimizationRoute {
        if (sdkInt < Build.VERSION_CODES.M) {
            return BatteryOptimizationRoute.AppDetails
        }
        if (canHandleAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {
            return BatteryOptimizationRoute.SettingsList
        }
        return BatteryOptimizationRoute.AppDetails
    }

    private fun createIntentForAction(
        action: String,
        packageName: String,
    ): Intent = Intent(action).apply { data = packageUri(packageName) }

    private fun packageUri(packageName: String): Uri = Uri.parse("package:$packageName")
}
