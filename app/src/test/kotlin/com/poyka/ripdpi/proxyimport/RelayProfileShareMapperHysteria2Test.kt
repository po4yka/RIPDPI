package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayProfileRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProfileShareMapperHysteria2Test {
    @Test
    fun `hysteria identity and credentials survive share mapping`() {
        val passwordFixture = "test-value"
        val obfsPasswordFixture = "obfs-test-value"
        val record =
            RelayProfileRecord(
                kind = RelayKindHysteria2,
                server = "203.0.113.9",
                serverName = "hy.example",
                serverPort = 443,
            )
        val credentials =
            RelayCredentialRecord(
                profileId = record.id,
                hysteriaPassword = passwordFixture,
                hysteriaSalamanderKey = obfsPasswordFixture,
                hysteriaInsecure = true,
            )

        val shareable = RelayProfileShareMapper.toShareable(record, credentials)!!
        val profile = shareable.profile as ProxyProfile.Hysteria2

        assertEquals("hy.example", profile.serverName)
        assertEquals(obfsPasswordFixture, profile.obfsPassword)
        assertEquals(true, profile.insecure)
        assertTrue(shareable.shareUri.contains("sni=hy.example"))
        assertTrue(shareable.shareUri.contains("insecure=1"))
    }
}
