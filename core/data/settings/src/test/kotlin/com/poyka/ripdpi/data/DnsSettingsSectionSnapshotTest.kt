package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Equivalence guard for the DNS snapshot mapper after it was migrated to
 * consume [DnsSettingsSection] instead of the whole `AppSettings` proto.
 *
 * The migrated `withDnsSnapshot` derives the snapshot through the section;
 * this test pins it against the direct `AppSettings.activeDnsSettings()`
 * reference path for the same payload — they must agree on every DNS field.
 */
class DnsSettingsSectionSnapshotTest {
    @Test
    fun `dns snapshot derived through the section matches the direct proto path`() {
        val settings =
            AppSettings
                .newBuilder()
                .setIpv6Enable(true)
                .setDnsMode("encrypted")
                .setEncryptedDnsHost("doh.example")
                .setEncryptedDnsPort(853)
                .build()

        val expected = settings.activeDnsSettings()
        val dns = settings.toSnapshot().dns

        assertEquals(expected.dnsIp, dns.dnsIp)
        assertEquals(expected.mode, dns.dnsMode)
        assertEquals(expected.providerId, dns.dnsProviderId)
        assertEquals(expected.encryptedDnsProtocol, dns.encryptedDnsProtocol)
        assertEquals(expected.encryptedDnsHost, dns.encryptedDnsHost)
        assertEquals(expected.encryptedDnsPort, dns.encryptedDnsPort)
        assertEquals(expected.encryptedDnsTlsServerName, dns.encryptedDnsTlsServerName)
        assertEquals(expected.encryptedDnsBootstrapIps, dns.encryptedDnsBootstrapIps)
        assertEquals(true, dns.ipv6Enabled)
    }
}
