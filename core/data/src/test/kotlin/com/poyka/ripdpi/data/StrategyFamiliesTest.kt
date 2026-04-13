package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategyFamiliesTest {
    @Test
    fun `strategy lane family labels cover ech families`() {
        assertEquals("ECH extension split", strategyLaneFamilyLabel("ech_split"))
        assertEquals("ECH TLS record split", strategyLaneFamilyLabel("ech_tlsrec"))
        assertEquals("Sequence overlap", strategyLaneFamilyLabel("seqovl"))
        assertEquals("TLS record + sequence overlap", strategyLaneFamilyLabel("tlsrec_seqovl"))
        assertEquals("Multi-disorder", strategyLaneFamilyLabel("multidisorder"))
        assertEquals("TLS record multi-disorder", strategyLaneFamilyLabel("tlsrec_multidisorder"))
        assertEquals("IP fragmentation", strategyLaneFamilyLabel("ipfrag2"))
        assertEquals("QUIC IP fragmentation", strategyLaneFamilyLabel("quic_ipfrag2"))
        assertEquals("Circular rotation (TLS record split)", strategyLaneFamilyLabel("circular_tlsrec_split"))
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
    fun `tcp lane derives tlsrec seqovl family`() {
        val family =
            deriveTcpStrategyFamily(
                listOf(
                    TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                    TcpChainStepModel(
                        kind = TcpChainStepKind.SeqOverlap,
                        marker = "midsld",
                        overlapSize = 12,
                        fakeMode = SeqOverlapFakeModeProfile,
                    ),
                ),
            )

        assertEquals("tlsrec_seqovl", family)
    }

    @Test
    fun `tcp lane derives tlsrec multidisorder family`() {
        val family =
            deriveTcpStrategyFamily(
                listOf(
                    TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                    TcpChainStepModel(kind = TcpChainStepKind.MultiDisorder, marker = "sniext"),
                    TcpChainStepModel(kind = TcpChainStepKind.MultiDisorder, marker = "host"),
                ),
            )

        assertEquals("tlsrec_multidisorder", family)
    }

    @Test
    fun `tcp lane prefixes circular family when rotation candidates exist`() {
        val family =
            deriveTcpStrategyFamily(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                        TcpChainStepModel(kind = TcpChainStepKind.Split, marker = "host+2"),
                    ),
                tcpRotationCandidateCount = 1,
            )

        assertEquals("circular_tlsrec_split", family)
    }

    @Test
    fun `quic lane distinguishes compat burst from realistic burst`() {
        assertEquals(
            "quic_compat_burst",
            deriveQuicStrategyFamily(
                udpSteps = listOf(UdpChainStepModel(kind = UdpChainStepKind.FakeBurst, count = 4)),
                desyncUdp = true,
                quicInitialMode = QuicInitialModeRouteAndCache,
                quicFakeProfile = QuicFakeProfileCompatDefault,
            ),
        )
        assertEquals(
            "quic_realistic_burst",
            deriveQuicStrategyFamily(
                udpSteps = listOf(UdpChainStepModel(kind = UdpChainStepKind.FakeBurst, count = 4)),
                desyncUdp = true,
                quicInitialMode = QuicInitialModeRouteAndCache,
                quicFakeProfile = QuicFakeProfileRealisticInitial,
            ),
        )
    }

    @Test
    fun `quic lane reports ip fragmentation when configured`() {
        assertEquals(
            "quic_ipfrag2",
            deriveQuicStrategyFamily(
                udpSteps = listOf(UdpChainStepModel(count = 0, kind = UdpChainStepKind.IpFrag2Udp, splitBytes = 8)),
                desyncUdp = true,
                quicInitialMode = QuicInitialModeRouteAndCache,
                quicFakeProfile = QuicFakeProfileDisabled,
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
