package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.data.diagnostics.DetectionResolverMode
import com.poyka.ripdpi.data.diagnostics.DnsPreset
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

    @Test
    fun directResolverConfigUsesCustomServers() {
        val config =
            AppSettings
                .newBuilder()
                .setDetectionCheckDnsResolverMode("direct")
                .setDetectionCheckDnsPreset("custom")
                .setDetectionCheckDnsDirectServers("9.9.9.9, 149.112.112.112")
                .build()
                .toDetectionResolverConfig()

        assertEquals(DetectionResolverMode.DIRECT, config.resolverMode)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), config.directDnsServers)
    }

    @Test
    fun dohResolverConfigUsesPresetUrlAndBootstrapServers() {
        val config =
            AppSettings
                .newBuilder()
                .setDetectionCheckDnsResolverMode("doh")
                .setDetectionCheckDnsPreset("google")
                .build()
                .toDetectionResolverConfig()

        assertEquals(DetectionResolverMode.DOH, config.resolverMode)
        assertEquals(DnsPreset.GOOGLE, config.dohPreset)
        assertEquals(DnsPreset.GOOGLE.servers, config.dohBootstrapIps)
    }

    @Test
    fun customDohResolverConfigUsesConfiguredEndpointHost() {
        val config =
            AppSettings
                .newBuilder()
                .setDetectionCheckDnsResolverMode("doh")
                .setDetectionCheckDnsPreset("custom")
                .setDetectionCheckDnsDirectServers("9.9.9.9")
                .setDetectionCheckDnsDohUrl("https://dns.quad9.net/dns-query")
                .build()
                .toDetectionResolverConfig()

        assertEquals(DetectionResolverMode.DOH, config.resolverMode)
        assertEquals("custom", config.dohPreset.id)
        assertEquals("https://dns.quad9.net/dns-query", config.dohPreset.dohUrl)
        assertEquals("dns.quad9.net", config.dohPreset.dohHost)
        assertEquals(listOf("9.9.9.9"), config.dohBootstrapIps)
    }
}
