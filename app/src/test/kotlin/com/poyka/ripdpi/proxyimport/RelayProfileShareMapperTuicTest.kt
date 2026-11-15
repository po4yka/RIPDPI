package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayProfileShareMapperTuicTest {
    @Test
    fun `saved TUIC preserves native settings and credentials through share`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindTuicV5,
                operatorName = "Node",
                server = "2001:db8::1",
                serverPort = 8443,
                serverName = "tls.example",
                tuicAlpn = "tuic,h3",
                tuicCongestionControl = "new_reno",
                udpEnabled = true,
            )
        val credentials = credentials(record.id)
        val share = requireNotNull(RelayProfileShareMapper.toShareable(record, credentials))
        val parsed = ProxyUriCodec.parse(share.shareUri) as ProxyProfile.Tuic

        assertEquals("Node", parsed.displayName)
        assertEquals("2001:db8::1", parsed.server)
        assertEquals(8443, parsed.serverPort)
        assertEquals("tls.example", parsed.serverName)
        assertEquals("tuic,h3", parsed.alpn)
        assertEquals("new_reno", parsed.congestionControl)
        assertEquals(credentials.tuicUuid, parsed.uuid)
        assertEquals(credentials.tuicPassword, parsed.password)
    }

    @Test
    fun `saved TUIC refuses unsupported or unrepresentable settings`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindTuicV5,
                server = "h.example",
                serverName = "h.example",
                udpEnabled = true,
            )
        val credentials = credentials(record.id)
        assertNotNull(RelayProfileShareMapper.toShareable(record, credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(udpEnabled = false), credentials))
        assertNull(
            RelayProfileShareMapper.toShareable(record.copy(tuicRootCertificatePem = "fixture PEM"), credentials),
        )
        assertNull(RelayProfileShareMapper.toShareable(record.copy(tuicZeroRtt = true), credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(tuicCongestionControl = "unknown"), credentials))
        assertNull(RelayProfileShareMapper.toShareable(record.copy(tuicAlpn = "h3,"), credentials))
        assertNull(RelayProfileShareMapper.toShareable(record, credentials.copy(tuicUuid = "invalid")))
    }

    private fun credentials(id: String) =
        RelayCredentialRecord(
            profileId = id,
            tuicUuid = "11111111-2222-3333-4444-555555555555",
            tuicPassword = "fixture+pass:word",
        )
}
