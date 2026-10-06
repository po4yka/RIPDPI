package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.mapRelayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RelayProfileMappingTest {
    @Test
    fun `supported imported transports preserve endpoint kind and candidate identity`() {
        val profiles =
            listOf(
                ProxyProfile.Trojan("id", "name", "g", "endpoint.example", 8443, CredentialFixture),
                ProxyProfile.Shadowsocks("id", "name", "g", "endpoint.example", 8443, "aes-128-gcm", CredentialFixture),
                ProxyProfile.AnyTls("id", "name", "g", "endpoint.example", 8443, "tls.example", CredentialFixture),
                ProxyProfile.VlessReality(
                    "id",
                    "name",
                    "g",
                    "endpoint.example",
                    8443,
                    Uuid,
                    PublicKey,
                    "ab",
                    "tls.example",
                ),
                ProxyProfile.Vless(
                    "id",
                    "name",
                    "g",
                    "endpoint.example",
                    8443,
                    Uuid,
                    "tls.example",
                    xhttpPath = "/path",
                ),
                ProxyProfile.Hysteria2("id", "name", "g", "endpoint.example", 8443, CredentialFixture),
                ProxyProfile.Ssh("id", "name", "g", "endpoint.example", 8443, "user", password = CredentialFixture),
                ProxyProfile.Mieru("id", "name", "g", "endpoint.example", 8443, "user", CredentialFixture),
            )
        val kinds = listOf("trojan", "shadowsocks", "anytls", "vless_reality", "vless", "hysteria2", "ssh", "mieru")
        profiles.zip(kinds).forEach { (profile, kind) ->
            val mapped = requireNotNull(mapRelayProfile(profile))
            assertEquals(kind, mapped.profile.kind)
            assertEquals("id", mapped.profile.id)
            assertEquals("id", mapped.credentials.profileId)
            assertEquals("endpoint.example", mapped.profile.server)
            assertEquals(8443, mapped.profile.serverPort)
        }
    }

    @Test
    fun `SSH key mapping excludes unused password and preserves host key policy`() {
        val profile =
            ProxyProfile.Ssh(
                "id",
                "name",
                "g",
                "endpoint.example",
                22,
                "user",
                authType = RelaySshAuthTypePrivateKey,
                password = CredentialFixture,
                privateKey = "fixture-key",
                privateKeyPassphrase = "fixture-passphrase",
                hostKeyFingerprint = "SHA256:fixture",
                strictHostKey = true,
            )
        val mapped = requireNotNull(mapRelayProfile(profile))
        assertNull(mapped.credentials.sshPassword)
        assertEquals(profile.privateKey, mapped.credentials.sshPrivateKey)
        assertEquals(profile.privateKeyPassphrase, mapped.credentials.sshPrivateKeyPassphrase)
        assertEquals(profile.hostKeyFingerprint, mapped.profile.sshHostKeyFingerprint)
        assertEquals(profile.strictHostKey, mapped.profile.sshStrictHostKey)
        assertFalse(mapped.profile.udpEnabled)
    }

    @Test
    fun `REALITY and xHTTP parameters survive transient mapping with explicit TLS override`() {
        val profile =
            ProxyProfile.VlessReality(
                "id",
                "name",
                "g",
                "endpoint.example",
                443,
                Uuid,
                PublicKey,
                "ab",
                "tls.example",
                fingerprint = "chrome",
                xhttpPath = "/configured",
                xhttpHost = "http.example",
                xhttpMode = "stream-up",
            )
        val mapped = requireNotNull(mapRelayProfile(profile, "candidate-slot", "firefox"))
        assertEquals("candidate-slot", mapped.credentials.profileId)
        assertEquals(Uuid, mapped.credentials.vlessUuid)
        assertEquals(PublicKey, mapped.profile.realityPublicKey)
        assertEquals("ab", mapped.profile.realityShortId)
        assertEquals("tls.example", mapped.profile.serverName)
        assertEquals("firefox", mapped.profile.vlessFingerprint)
        assertEquals(RelayVlessTransportXhttp, mapped.profile.vlessTransport)
        assertEquals("/configured", mapped.profile.xhttpPath)
        assertEquals("http.example", mapped.profile.xhttpHost)
        assertEquals("stream-up", mapped.profile.xhttpMode)
    }

    @Test
    fun `plain VLESS without supported native transport remains unavailable`() {
        val profile = ProxyProfile.Vless("id", "name", "g", "endpoint.example", 443, Uuid, "tls.example")
        assertNull(mapRelayProfile(profile))
    }

    private companion object {
        const val CredentialFixture = "mapping-credential-fixture"
        const val Uuid = "11111111-1111-4111-8111-111111111111"
        const val PublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
