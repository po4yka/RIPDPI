package com.poyka.ripdpi.fragments

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.*
import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences.DesyncMethod.*
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences.HostsMode.*
import com.poyka.ripdpi.utility.*

class RipDpiUISettingsFragment : PreferenceFragmentCompat() {

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.ripdpi_ui_settings, rootKey)

        setEditTextPreferenceListener("ripdpi_proxy_ip") { checkIp(it) }
        setEditTestPreferenceListenerPort("ripdpi_proxy_port")
        setEditTestPreferenceListenerInt(
            "ripdpi_max_connections",
            1,
            Short.MAX_VALUE.toInt()
        )
        setEditTestPreferenceListenerInt(
            "ripdpi_buffer_size",
            1,
            Int.MAX_VALUE / 4
        )
        setEditTestPreferenceListenerInt("ripdpi_default_ttl", 0, 255)
        setEditTestPreferenceListenerInt(
            "ripdpi_split_position",
            Int.MIN_VALUE,
            Int.MAX_VALUE
        )
        setEditTestPreferenceListenerInt("ripdpi_fake_ttl", 1, 255)
        setEditTestPreferenceListenerInt(
            "ripdpi_tlsrec_position",
            2 * Short.MIN_VALUE,
            2 * Short.MAX_VALUE,
        )

        findPreferenceNotNull<EditTextPreference>("ripdpi_oob_data")
            .setOnBindEditTextListener {
                it.filters = arrayOf(android.text.InputFilter.LengthFilter(1))
            }

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
        val desyncMethod =
            findPreferenceNotNull<ListPreference>("ripdpi_desync_method")
                .value.let { RipDpiProxyUIPreferences.DesyncMethod.fromName(it) }
        val hostsMode = findPreferenceNotNull<ListPreference>("ripdpi_hosts_mode")
            .value.let { RipDpiProxyUIPreferences.HostsMode.fromName(it) }

        val hostsBlacklist = findPreferenceNotNull<EditTextPreference>("ripdpi_hosts_blacklist")
        val hostsWhitelist = findPreferenceNotNull<EditTextPreference>("ripdpi_hosts_whitelist")
        val desyncHttp = findPreferenceNotNull<CheckBoxPreference>("ripdpi_desync_http")
        val desyncHttps = findPreferenceNotNull<CheckBoxPreference>("ripdpi_desync_https")
        val desyncUdp = findPreferenceNotNull<CheckBoxPreference>("ripdpi_desync_udp")
        val splitPosition = findPreferenceNotNull<EditTextPreference>("ripdpi_split_position")
        val splitAtHost = findPreferenceNotNull<CheckBoxPreference>("ripdpi_split_at_host")
        val ttlFake = findPreferenceNotNull<EditTextPreference>("ripdpi_fake_ttl")
        val fakeSni = findPreferenceNotNull<EditTextPreference>("ripdpi_fake_sni")
        val fakeOffset = findPreferenceNotNull<EditTextPreference>("ripdpi_fake_offset")
        val oobChar = findPreferenceNotNull<EditTextPreference>("ripdpi_oob_data")
        val udpFakeCount = findPreferenceNotNull<EditTextPreference>("ripdpi_udp_fake_count")
        val hostMixedCase = findPreferenceNotNull<CheckBoxPreference>("ripdpi_host_mixed_case")
        val domainMixedCase = findPreferenceNotNull<CheckBoxPreference>("ripdpi_domain_mixed_case")
        val hostRemoveSpaces =
            findPreferenceNotNull<CheckBoxPreference>("ripdpi_host_remove_spaces")
        val splitTlsRec = findPreferenceNotNull<CheckBoxPreference>("ripdpi_tlsrec_enabled")
        val splitTlsRecPosition =
            findPreferenceNotNull<EditTextPreference>("ripdpi_tlsrec_position")
        val splitTlsRecAtSni = findPreferenceNotNull<CheckBoxPreference>("ripdpi_tlsrec_at_sni")

        hostsBlacklist.isVisible = hostsMode == Blacklist
        hostsWhitelist.isVisible = hostsMode == Whitelist

        val desyncEnabled = desyncMethod != None
        splitPosition.isVisible = desyncEnabled
        splitAtHost.isVisible = desyncEnabled

        val isFake = desyncMethod == Fake
        ttlFake.isVisible = isFake
        fakeSni.isVisible = isFake
        fakeOffset.isVisible = isFake

        val isOob = desyncMethod == OOB || desyncMethod == DISOOB
        oobChar.isVisible = isOob

        val desyncAllProtocols =
            !desyncHttp.isChecked && !desyncHttps.isChecked && !desyncUdp.isChecked

        val desyncHttpEnabled = desyncAllProtocols || desyncHttp.isChecked
        hostMixedCase.isEnabled = desyncHttpEnabled
        domainMixedCase.isEnabled = desyncHttpEnabled
        hostRemoveSpaces.isEnabled = desyncHttpEnabled

        val desyncUdpEnabled = desyncAllProtocols || desyncUdp.isChecked
        udpFakeCount.isEnabled = desyncUdpEnabled

        val desyncHttpsEnabled = desyncAllProtocols || desyncHttps.isChecked
        splitTlsRec.isEnabled = desyncHttpsEnabled
        val tlsRecEnabled = desyncHttpsEnabled && splitTlsRec.isChecked
        splitTlsRecPosition.isEnabled = tlsRecEnabled
        splitTlsRecAtSni.isEnabled = tlsRecEnabled
    }
}
