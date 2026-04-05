package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiModelsTest {
    @Test
    fun `dns ui state defaults match canonical encrypted settings`() {
        val defaultDns = canonicalDefaultEncryptedDnsSettings()
        val state = DnsUiState()

        assertEquals(defaultDns.dnsIp, state.dnsIp)
        assertEquals(defaultDns.providerId, state.dnsProviderId)
        assertEquals(defaultDns.encryptedDnsProtocol, state.encryptedDnsProtocol)
        assertEquals(defaultDns.encryptedDnsHost, state.encryptedDnsHost)
        assertEquals(defaultDns.encryptedDnsPort, state.encryptedDnsPort)
        assertEquals(defaultDns.encryptedDnsTlsServerName, state.encryptedDnsTlsServerName)
        assertEquals(defaultDns.encryptedDnsBootstrapIps, state.encryptedDnsBootstrapIps)
        assertEquals(defaultDns.encryptedDnsDohUrl, state.encryptedDnsDohUrl)
        assertEquals(defaultDns.summary(), state.dnsSummary)
    }

    @Test
    fun `adaptive fallback ui defaults stay enabled with all triggers`() {
        val state = AdaptiveFallbackUiState()

        assertTrue(state.enabled)
        assertTrue(state.torst)
        assertTrue(state.tlsErr)
        assertTrue(state.httpRedirect)
        assertTrue(state.connectFailure)
        assertTrue(state.autoSort)
        assertEquals(4, state.triggerCount)
        assertTrue(state.usesDefaultProfile)
    }
}
