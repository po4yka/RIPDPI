package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionSettingsUiModelTest {
    @Test
    fun cloudflarePresetPopulatesLockedDnsFields() {
        val state =
            DetectionSettingsUiState
                .from(AppSettings.getDefaultInstance())
                .selectDnsPreset(DetectionDnsPreset.CLOUDFLARE)

        assertEquals(DetectionDnsPreset.CLOUDFLARE, state.dnsPreset)
        assertEquals("1.1.1.1, 1.0.0.1", state.dnsDirectServers)
        assertEquals("https://cloudflare-dns.com/dns-query", state.dnsDohUrl)
        assertFalse(state.dnsFieldsEditable)
    }

    @Test
    fun customDnsPresetKeepsFieldsEditable() {
        val state =
            DetectionSettingsUiState.from(
                AppSettings
                    .newBuilder()
                    .setDetectionCheckDnsPreset("custom")
                    .setDetectionCheckDnsDirectServers("9.9.9.9")
                    .setDetectionCheckDnsDohUrl("https://dns.example/dns-query")
                    .build(),
            )

        assertEquals(DetectionDnsPreset.CUSTOM, state.dnsPreset)
        assertEquals("9.9.9.9", state.dnsDirectServers)
        assertEquals("https://dns.example/dns-query", state.dnsDohUrl)
        assertTrue(state.dnsFieldsEditable)
    }

    @Test
    fun disabledNetworkRequestsDimDependentControls() {
        val state =
            DetectionSettingsUiState.from(
                AppSettings
                    .newBuilder()
                    .setDetectionCheckNetworkRequestsEnabled(false)
                    .build(),
            )

        assertEquals(0.38f, state.networkDependentAlpha, 0.001f)
        assertFalse(state.networkDependentEnabled)
    }

    @Test
    fun customPortRangeCountsInclusiveValidPorts() {
        val state =
            DetectionSettingsUiState.from(
                AppSettings
                    .newBuilder()
                    .setDetectionCheckPortRangeMode("custom")
                    .setDetectionCheckCustomPortStart(1080)
                    .setDetectionCheckCustomPortEnd(1082)
                    .build(),
            )

        assertEquals(DetectionPortRangeMode.CUSTOM, state.portRangeMode)
        assertEquals(3, state.customPortCount)
        assertTrue(state.customPortRangeValid)
    }
}
