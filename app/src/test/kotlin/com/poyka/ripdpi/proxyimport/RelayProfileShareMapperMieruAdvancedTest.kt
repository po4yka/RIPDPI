package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.MieruHandshakeDefault
import com.poyka.ripdpi.data.MieruTcpFragment
import com.poyka.ripdpi.data.MieruTrafficPattern
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.serialization.RipDpiJson
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayProfileShareMapperMieruAdvancedTest {
    @Test
    fun `saved Mieru share retains explicit default mode and optional pattern presence`() {
        val pattern = MieruTrafficPattern(seed = 42, unlockAll = false, tcpFragment = MieruTcpFragment())
        val original =
            RelayProfileRecord(
                kind = RelayKindMieru,
                server = "example.test",
                serverPort = 443,
                mieruHandshakeMode = MieruHandshakeDefault,
                mieruTrafficPattern = pattern,
            )
        val stored =
            RipDpiJson.decodeFromString(
                RelayProfileRecord.serializer(),
                RipDpiJson.encodeToString(RelayProfileRecord.serializer(), original),
            )
        val credentialFixture =
            RelayCredentialRecord(profileId = stored.id, mieruUsername = "alice", mieruPassword = "fixture")
        val shared = requireNotNull(RelayProfileShareMapper.toShareable(stored, credentialFixture))
        val parsed = ProxyUriCodec.parse(shared.shareUri) as ProxyProfile.Mieru
        assertEquals(MieruHandshakeDefault, parsed.handshakeMode)
        assertEquals(pattern, parsed.trafficPattern)
    }
}
