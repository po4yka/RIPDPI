package com.poyka.ripdpi.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import com.poyka.ripdpi.BuildConfig
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.utility.*

class MainSettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private val TAG: String = MainSettingsFragment::class.java.simpleName

        fun setTheme(name: String) =
            themeByName(name)?.let {
                AppCompatDelegate.setDefaultNightMode(it)
            } ?: throw IllegalStateException("Invalid value for app_theme: $name")

        private fun themeByName(name: String): Int? = when (name) {
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> {
                Log.w(TAG, "Invalid value for app_theme: $name")
                null
            }
        }
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        setEditTextPreferenceListener("dns_ip") {
            it.isBlank() || checkNotLocalIp(it)
        }

        findPreferenceNotNull<DropDownPreference>("app_theme")
            .setOnPreferenceChangeListener { _, newValue ->
                setTheme(newValue as String)
                true
            }

        val switchCommandLineSettings = findPreferenceNotNull<SwitchPreference>(
            "ripdpi_enable_cmd_settings"
        )
        val uiSettings = findPreferenceNotNull<Preference>("ripdpi_ui_settings")
        val cmdSettings = findPreferenceNotNull<Preference>("ripdpi_cmd_settings")

        val setRipDpiSettingsMode = { enable: Boolean ->
            uiSettings.isEnabled = !enable
            cmdSettings.isEnabled = enable
        }

        setRipDpiSettingsMode(switchCommandLineSettings.isChecked)

        switchCommandLineSettings.setOnPreferenceChangeListener { _, newValue ->
            setRipDpiSettingsMode(newValue as Boolean)
            true
        }

        findPreferenceNotNull<Preference>("version").summary = BuildConfig.VERSION_NAME

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun updatePreferences() {
        val mode = findPreferenceNotNull<ListPreference>("ripdpi_mode")
            .value.let { Mode.fromString(it) }
        val dns = findPreferenceNotNull<EditTextPreference>("dns_ip")
        val ipv6 = findPreferenceNotNull<SwitchPreference>("ipv6_enable")

        when (mode) {
            Mode.VPN -> {
                dns.isVisible = true
                ipv6.isVisible = true
            }

            Mode.Proxy -> {
                dns.isVisible = false
                ipv6.isVisible = false
            }
        }
    }
}
