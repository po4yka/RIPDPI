package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.Hysteria2PortHopping
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProfileShareMapperHysteria2Test {
    @Test
    fun `hysteria identity and credentials survive share mapping`() {
        val passwordFixture = "test-value"
        val obfsPasswordFixture = "obfs-test-value"
        val pinFixture = "ef".repeat(32)
        val record =
            RelayProfileRecord(
                kind = RelayKindHysteria2,
                server = "203.0.113.9",
                serverName = "hy.example",
                serverPort = 443,
                hysteriaPortHopping = Hysteria2PortHopping("20000-21000", 5000, 15000),
            )
        val credentials =
            RelayCredentialRecord(
                profileId = record.id,
                hysteriaPassword = passwordFixture,
                hysteriaSalamanderKey = obfsPasswordFixture,
                hysteriaInsecure = true,
                hysteriaPinSha256 = pinFixture,
            )

        val shareable = RelayProfileShareMapper.toShareable(record, credentials)!!
        val profile = shareable.profile as ProxyProfile.Hysteria2

        assertEquals(record.hysteriaPortHopping, profile.portHopping)
        val reimported = ProxyUriCodec.parse(shareable.shareUri) as ProxyProfile.Hysteria2
        assertEquals(record.hysteriaPortHopping, reimported.portHopping)
        assertEquals("hy.example", profile.serverName)
        assertEquals(obfsPasswordFixture, profile.obfsPassword)
        assertEquals(true, profile.insecure)
        assertTrue(shareable.shareUri.contains("sni=hy.example"))
        assertTrue(shareable.shareUri.contains("insecure=1"))
        // REL-3: a shared insecure profile must keep its pin, or the recipient
        // imports an accept-any-certificate profile.
        assertEquals(pinFixture, profile.pinSha256)
        assertTrue(shareable.shareUri.contains("pinSHA256=$pinFixture"))
    }
}
