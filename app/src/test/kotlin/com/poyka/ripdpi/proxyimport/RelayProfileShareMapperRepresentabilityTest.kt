package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayProfileShareMapperRepresentabilityTest {
    @Test
    fun `saved Mieru tuning survives import of its share link`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindMieru,
                server = "proxy.example",
                mieruMtu = 1328,
                mieruMultiplexing = "high",
            )
        val credentials =
            RelayCredentialRecord(
                profileId = record.id,
                mieruUsername = "fixture-user",
                mieruPassword = "fixture-pass",
            )

        val shared = requireNotNull(RelayProfileShareMapper.toShareable(record, credentials))
        val imported = ProxyUriCodec.parse(shared.shareUri) as ProxyProfile.Mieru

        assertEquals(1328, imported.mtu)
        assertEquals("high", imported.multiplexing)
        assertEquals("tcp", imported.protocol)
    }

    @Test
    fun `Trojan refuses links that lose custom trust or turn UDP on`() {
        val record = RelayProfileRecord(kind = RelayKindTrojan, server = "proxy.example", udpEnabled = true)
        val credentials = RelayCredentialRecord(profileId = record.id, trojanPassword = "fixture-pass")

        assertNotNull(RelayProfileShareMapper.toShareable(record, credentials))
        assertNull(
            RelayProfileShareMapper.toShareable(record.copy(trojanRootCertificatePem = "custom root"), credentials),
        )
        assertNull(RelayProfileShareMapper.toShareable(record.copy(udpEnabled = false), credentials))
    }

    @Test
    fun `syntactically parseable links still require a valid port and REALITY material`() {
        val trojan =
            RelayProfileRecord(kind = RelayKindTrojan, server = "proxy.example", serverPort = 65535, udpEnabled = true)
        val trojanCredentials = RelayCredentialRecord(profileId = trojan.id, trojanPassword = "fixture-pass")
        assertNotNull(RelayProfileShareMapper.toShareable(trojan, trojanCredentials))
        assertNull(RelayProfileShareMapper.toShareable(trojan.copy(serverPort = 65536), trojanCredentials))

        val reality =
            RelayProfileRecord(
                kind = RelayKindVlessReality,
                server = "proxy.example",
                realityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=",
                udpEnabled = true,
            )
        val realityCredentials =
            RelayCredentialRecord(profileId = reality.id, vlessUuid = "00000000-0000-0000-0000-000000000000")
        assertNotNull(RelayProfileShareMapper.toShareable(reality, realityCredentials))
        assertNull(RelayProfileShareMapper.toShareable(reality.copy(realityPublicKey = "broken"), realityCredentials))
        assertNotNull(RelayProfileShareMapper.toShareable(reality, realityCredentials.copy(vlessUuid = "fixture")))
        assertNull(RelayProfileShareMapper.toShareable(reality, realityCredentials.copy(vlessUuid = "0".repeat(31))))
    }

    @Test
    fun `Naive refuses links that lose path or server identity`() {
        val record = RelayProfileRecord(kind = RelayKindNaiveProxy, server = "proxy.example")
        val credentials = RelayCredentialRecord(profileId = record.id, naiveUsername = "fixture-user")

        assertNotNull(RelayProfileShareMapper.toShareable(record, credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(naivePath = "/gateway"), credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(serverName = "tls.example"), credentials))
    }
}
