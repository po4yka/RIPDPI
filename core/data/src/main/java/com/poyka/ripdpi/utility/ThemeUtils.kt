package com.poyka.ripdpi.utility

import androidx.appcompat.app.AppCompatDelegate
import logcat.LogPriority
import logcat.logcat

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
                logcat("ThemeUtils", LogPriority.WARN) { "Invalid value for app_theme: $name" }
                return
            }
        }
    AppCompatDelegate.setDefaultNightMode(mode)
}
