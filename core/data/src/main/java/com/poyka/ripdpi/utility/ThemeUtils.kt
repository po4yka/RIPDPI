package com.poyka.ripdpi.utility

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

private const val TAG = "ThemeUtils"

fun applyTheme(name: String) {
    val mode =
        when (name) {
            "system" -> {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }

            "light" -> {
                AppCompatDelegate.MODE_NIGHT_NO
            }

            "dark" -> {
                AppCompatDelegate.MODE_NIGHT_YES
            }

            else -> {
                Log.w(TAG, "Invalid value for app_theme: $name")
                return
            }
        }
    AppCompatDelegate.setDefaultNightMode(mode)
}
