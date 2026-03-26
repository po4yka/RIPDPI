package com.poyka.ripdpi.debug

import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDnsCrypt
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class PacketSmokeNetworkSupportTest {
    @Test
    fun `success presets use the expected AdGuard endpoints`() {
        val doh = PacketSmokeEncryptedDnsPresets.success(EncryptedDnsProtocolDoh)
        val dot = PacketSmokeEncryptedDnsPresets.success(EncryptedDnsProtocolDot)
        val doq = PacketSmokeEncryptedDnsPresets.success(EncryptedDnsProtocolDoq)

        assertEquals(DnsProviderCustom, doh.providerId)
        assertEquals("https://unfiltered.adguard-dns.com/dns-query", doh.expectedResolverEndpoint)
        assertEquals("unfiltered.adguard-dns.com:853", dot.expectedResolverEndpoint)
        assertEquals("unfiltered.adguard-dns.com:853", doq.expectedResolverEndpoint)
        assertEquals(listOf("94.140.14.140", "94.140.14.141"), doh.bootstrapIps)
        assertEquals(listOf("94.140.14.140", "94.140.14.141"), dot.bootstrapIps)
        assertEquals(listOf("94.140.14.140", "94.140.14.141"), doq.bootstrapIps)
    }

    @Test
    fun `dnscrypt preset decodes official stamp`() {
        val preset = PacketSmokeEncryptedDnsPresets.success(EncryptedDnsProtocolDnsCrypt)

        assertEquals(DnsProviderCustom, preset.providerId)
        assertEquals("94.140.14.140", preset.host)
        assertEquals(5443, preset.port)
        assertEquals("2.dnscrypt.unfiltered.ns1.adguard.com", preset.dnscryptProviderName)
        assertEquals(
            "b5e844d6b83a3e3e1268eb681fbea70de3c5fa68dbd688141dbf2ec066b2dafa",
            preset.dnscryptPublicKey,
        )
        assertEquals(listOf("94.140.14.140"), preset.bootstrapIps)
        assertEquals("94.140.14.140:5443", preset.expectedResolverEndpoint)
    }

    @Test
    fun `fault preset overrides bootstrap ips only`() {
        val success = PacketSmokeEncryptedDnsPresets.success(EncryptedDnsProtocolDoq)
        val fault = PacketSmokeEncryptedDnsPresets.fault(EncryptedDnsProtocolDoq)

        assertEquals(success.protocol, fault.protocol)
        assertEquals(success.host, fault.host)
        assertEquals(success.port, fault.port)
        assertEquals(success.expectedResolverEndpoint, fault.expectedResolverEndpoint)
        assertEquals(listOf(PacketSmokeFaultBootstrapIp), fault.bootstrapIps)
    }

    @Test
    fun `dns packet codec decodes compressed A answers`() {
        val queryId = 0x1234
        val response =
            byteArrayOf(
                0x12,
                0x34,
                0x81.toByte(),
                0x80.toByte(),
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x07,
                0x65,
                0x78,
                0x61,
                0x6d,
                0x70,
                0x6c,
                0x65,
                0x03,
                0x63,
                0x6f,
                0x6d,
                0x00,
                0x00,
                0x01,
                0x00,
                0x01,
                0xC0.toByte(),
                0x0C,
                0x00,
                0x01,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x3C,
                0x00,
                0x04,
                0x5D,
                0xB8.toByte(),
                0xD8.toByte(),
                0x22,
            )

        val decoded = DebugDnsPacketCodec.decodeResponse(response, queryId)

        assertEquals(queryId, decoded.requestId)
        assertEquals(0, decoded.rcode)
        assertEquals(listOf("93.184.216.34"), decoded.answers)
    }

    @Test
    fun `debug probe failure keeps exception metadata`() {
        val failure = SocketTimeoutException("timed out").toDebugProbeFailure()

        assertEquals(SocketTimeoutException::class.java.name, failure.errorClass)
        assertEquals("timed out", failure.errorMessage)
    }

    @Test
    fun `dns query encoder emits a standard question section`() {
        val packet = DebugDnsPacketCodec.buildQuery("example.com", requestId = 0x4321)

        assertTrue(packet.isNotEmpty())
        assertEquals(0x43, packet[0].toInt() and 0xFF)
        assertEquals(0x21, packet[1].toInt() and 0xFF)
        assertEquals(1, packet[5].toInt() and 0xFF)
    }

    @Test
    fun `packet smoke phase parser defaults to single`() {
        assertEquals(PacketSmokePhase.SINGLE, PacketSmokePhase.fromArgument(null))
        assertEquals(PacketSmokePhase.SINGLE, PacketSmokePhase.fromArgument("unknown"))
        assertEquals(PacketSmokePhase.PREPARE, PacketSmokePhase.fromArgument("prepare"))
        assertEquals(PacketSmokePhase.ASSERT, PacketSmokePhase.fromArgument("assert"))
    }

    @Test
    fun `prepare state json round trips`() {
        val state =
            PacketSmokePrepareState(
                scenarioId = "android_vpn_doh_family",
                deviceProfile = "physical_indirect",
                mode = "VPN",
                status = "Running",
                proxySessions = 7,
                txPackets = 10,
                rxPackets = 12,
                txBytes = 1000,
                rxBytes = 2000,
                dnsQueriesTotal = 3,
                dnsFailuresTotal = 1,
                restartCount = 2,
                capturedAtEpochMs = 1234,
                expectedResolverId = DnsProviderCustom,
                expectedResolverProtocol = EncryptedDnsProtocolDoh,
                expectedResolverEndpoint = "https://unfiltered.adguard-dns.com/dns-query",
                expectedDnsHost = "example.com",
            )

        val decoded = PacketSmokePrepareState.fromJson(state.toJson())

        assertEquals(state, decoded)
    }

    @Test
    fun `runner probe result json round trips`() {
        val result =
            PacketSmokeRunnerProbeResult(
                requestId = "req-1",
                scenarioId = "android_vpn_host_autolearn_family",
                probeType = "dns",
                host = PacketSmokeMapDnsAddress,
                port = PacketSmokeMapDnsPort,
                ok = false,
                queryHost = "example.com",
                rcode = 2,
                answers = listOf("93.184.216.34"),
                latencyMs = 42,
                localAddress = "10.0.0.2",
                localPort = 53000,
                errorClass = SocketTimeoutException::class.java.name,
                errorMessage = "timed out",
            )

        val decoded = PacketSmokeRunnerProbeResult.fromJson(result.toJson())

        assertEquals(result, decoded)
    }
}
