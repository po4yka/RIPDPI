package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayProfileShareMapperTrojanTest {
    @Test
    fun `trojan sni that differs from the server survives share mapping`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindTrojan,
                server = "203.0.113.5",
                serverName = "cdn.example",
                serverPort = 443,
            )
        val passwordFixture = "test-value"
        val credentials = RelayCredentialRecord(profileId = record.id, trojanPassword = passwordFixture)

        val shareable = RelayProfileShareMapper.toShareable(record, credentials)!!

        val reimported = ProxyUriCodec.parse(shareable.shareUri) as ProxyProfile.Trojan
        assertEquals("cdn.example", reimported.serverName)
    }
}
