package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProfileShareMapperVlessRealityTest {
    @Test
    fun `vless reality xhttp record preserves xhttp mode in profile and uri`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindVlessReality,
                server = "edge.example.com",
                serverPort = 443,
                serverName = "cdn.example.com",
                realityPublicKey = validRealityPublicKey,
                realityShortId = "abcd1234",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/tunnel",
                xhttpHost = "carrier.example.com",
                xhttpMode = "stream-one",
            )
        val credentials =
            RelayCredentialRecord(
                profileId = record.id,
                vlessUuid = validVlessUuid,
            )

        val shareable = RelayProfileShareMapper.toShareable(record, credentials)

        assertNotNull(shareable)
        val profile = shareable!!.profile as ProxyProfile.VlessReality
        assertEquals("stream-one", profile.xhttpMode)
        assertTrue(shareable.shareUri.startsWith("vless://"))
        assertTrue(shareable.shareUri.contains("type=xhttp"))
        assertTrue(shareable.shareUri.contains("mode=stream-one"))
    }

    @Test
    fun `empty xhttp fields and empty flow remain explicit in share uri`() {
        val record =
            RelayProfileRecord(
                kind = RelayKindVlessReality,
                server = "edge.example.com",
                serverName = "edge.example.com",
                realityPublicKey = validRealityPublicKey,
                vlessFlow = "",
                vlessFingerprint = "firefox",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "",
                xhttpHost = "",
            )
        val credentials = RelayCredentialRecord(profileId = record.id, vlessUuid = validVlessUuid)

        val shareable = RelayProfileShareMapper.toShareable(record, credentials)!!
        val profile = shareable.profile as ProxyProfile.VlessReality

        assertEquals("", profile.flow)
        assertEquals("firefox", profile.fingerprint)
        assertEquals("", profile.xhttpPath)
        assertEquals("", profile.xhttpHost)
        assertTrue(shareable.shareUri.contains("type=xhttp"))
        assertTrue(shareable.shareUri.contains("flow="))
        assertTrue(shareable.shareUri.contains("fp=firefox"))
    }
}

private const val validRealityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="
private const val validVlessUuid = "00000000-0000-0000-0000-000000000000"
