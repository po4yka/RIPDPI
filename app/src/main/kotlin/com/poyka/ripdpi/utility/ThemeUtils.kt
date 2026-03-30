package com.poyka.ripdpi.utility

import androidx.appcompat.app.AppCompatDelegate
import co.touchlab.kermit.Logger

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
                Logger.withTag("ThemeUtils").w { "Invalid value for app_theme: $name" }
                return
            }
        }
    AppCompatDelegate.setDefaultNightMode(mode)
}
