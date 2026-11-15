package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayProfileShareMapperMieruTest {
    @Test
    fun `saved Mieru transport options survive export and reimport`() {
        for (multiplexing in listOf("off", "low", "middle", "high")) {
            for (mtu in listOf(1280, 1380, 1500)) {
                val record =
                    RelayProfileRecord(
                        kind = RelayKindMieru,
                        server = "mieru.example",
                        serverPort = 8443,
                        mieruProtocol = "tcp",
                        mieruMultiplexing = multiplexing,
                        mieruMtu = mtu,
                    )
                val credentials =
                    RelayCredentialRecord(
                        profileId = record.id,
                        mieruUsername = "audit-user",
                        mieruPassword = "fixture-password",
                    )

                val shareable = requireNotNull(RelayProfileShareMapper.toShareable(record, credentials))
                val reimported = ProxyUriCodec.parse(shareable.shareUri) as ProxyProfile.Mieru

                assertEquals("tcp", reimported.protocol)
                assertEquals(multiplexing, reimported.multiplexing)
                assertEquals(mtu, reimported.mtu)
                assertEquals("audit-user", reimported.username)
                assertEquals("fixture-password", reimported.password)
            }
        }
    }
}
