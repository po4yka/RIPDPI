package com.poyka.ripdpi.fragments

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.poyka.ripdpi.R

class RipDpiCommandLineSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.ripdpi_cmd_settings, rootKey)
    }
}
