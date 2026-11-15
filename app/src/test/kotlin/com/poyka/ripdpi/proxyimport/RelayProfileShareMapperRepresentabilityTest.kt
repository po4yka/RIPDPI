package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindTrojan
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
    fun `Naive refuses links that lose path or server identity`() {
        val record = RelayProfileRecord(kind = RelayKindNaiveProxy, server = "proxy.example")
        val credentials = RelayCredentialRecord(profileId = record.id, naiveUsername = "fixture-user")

        assertNotNull(RelayProfileShareMapper.toShareable(record, credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(naivePath = "/gateway"), credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(serverName = "tls.example"), credentials))
    }
}
