package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyFamiliesTest {
    @Test
    fun `strategy lane family labels cover ech families`() {
        assertEquals("ECH extension split", strategyLaneFamilyLabel("ech_split"))
        assertEquals("ECH TLS record split", strategyLaneFamilyLabel("ech_tlsrec"))
    }

    @Test
    fun `tcp lane keeps tls hostfake separate from generic desync`() {
        val family =
            deriveTcpStrategyFamily(
                listOf(
                    TcpChainStepModel(
                        kind = TcpChainStepKind.TlsRec,
                        marker = "extlen",
                    ),
                    TcpChainStepModel(
                        kind = TcpChainStepKind.HostFake,
                        marker = "endhost+8",
                        midhostMarker = "midsld",
                        fakeHostTemplate = "googlevideo.com",
                    ),
                ),
            )

        assertEquals("hostfake", family)
    }

    @Test
    fun `quic lane distinguishes compat burst from realistic burst`() {
        assertEquals(
            "quic_compat_burst",
            deriveQuicStrategyFamily(
                desyncUdp = true,
                quicInitialMode = QuicInitialModeRouteAndCache,
                quicFakeProfile = QuicFakeProfileCompatDefault,
            ),
        )
        assertEquals(
            "quic_realistic_burst",
            deriveQuicStrategyFamily(
                desyncUdp = true,
                quicInitialMode = QuicInitialModeRouteAndCache,
                quicFakeProfile = QuicFakeProfileRealisticInitial,
            ),
        )
    }

    @Test
    fun `dns lane distinguishes plain dns from encrypted dns`() {
        val plain =
            ActiveDnsSettings(
                mode = DnsModePlainUdp,
                providerId = DnsProviderCustom,
                dnsIp = "8.8.8.8",
                encryptedDnsProtocol = "",
                encryptedDnsHost = "",
                encryptedDnsPort = 0,
                encryptedDnsTlsServerName = "",
                encryptedDnsBootstrapIps = emptyList(),
                encryptedDnsDohUrl = "",
                encryptedDnsDnscryptProviderName = "",
                encryptedDnsDnscryptPublicKey = "",
            )
        val encrypted =
            ActiveDnsSettings(
                mode = DnsModeEncrypted,
                providerId = DnsProviderCloudflare,
                dnsIp = "1.1.1.1",
                encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                encryptedDnsHost = "cloudflare-dns.com",
                encryptedDnsPort = 443,
                encryptedDnsTlsServerName = "cloudflare-dns.com",
                encryptedDnsBootstrapIps = listOf("1.1.1.1"),
                encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
                encryptedDnsDnscryptProviderName = "",
                encryptedDnsDnscryptPublicKey = "",
            )

        assertEquals("dns_plain_udp", plain.strategyFamily())
        assertEquals("Plain DNS", plain.strategyLabel())
        assertEquals("dns_encrypted_doh", encrypted.strategyFamily())
        assertEquals("Cloudflare DoH", encrypted.strategyLabel())
    }
}
