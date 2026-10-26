package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayMieruMultiplexingHigh
import com.poyka.ripdpi.data.RelayMieruProtocolUdp
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientRelayRuntimeConfigResolverTest : UpstreamRelayRuntimeConfigResolverTestFixture() {
    @Test
    fun `transient candidate resolution uses candidate secrets and never persists active selection`() =
        runTest {
            val profiles = TestRelayProfileStore()
            val credentials = TestRelayCredentialStore()
            val active = RelayProfileRecord(id = "active", kind = RelayKindTrojan, server = "active.example")
            profiles.save(active)
            val candidate =
                RelayProfileRecord(
                    id = "candidate",
                    kind = RelayKindTrojan,
                    server = "candidate.example",
                    serverPort = 8443,
                    serverName = "tls.example",
                )
            val secret = credentialFixture("candidate")
            val resolved =
                resolver(profiles, credentials).resolveTransient(
                    candidate,
                    RelayCredentialRecord(profileId = candidate.id, trojanPassword = secret),
                    OwnedRelayQuicMigrationConfig(bindLowPort = true, migrateAfterHandshake = true),
                    TlsFingerprintProfileChromeStable,
                    emptyMap(),
                )
            assertEquals(candidate.id, resolved.profileId)
            assertEquals(candidate.server, resolved.server)
            assertEquals(8443, resolved.serverPort)
            assertEquals("tls.example", resolved.serverName)
            assertEquals(secret, resolved.trojanPassword)
            assertTrue(resolved.quicBindLowPort)
            assertTrue(resolved.quicMigrateAfterHandshake)
            assertEquals(listOf(active), profiles.list())
            assertTrue(credentials.credentials.isEmpty())
        }

    @Test
    fun `transient ssh resolution keeps the profile auth type and host-key pin`() =
        runTest {
            val fingerprint = "SHA256:" + "A".repeat(43)
            val profile =
                RelayProfileRecord(
                    id = "ssh-candidate",
                    kind = RelayKindSsh,
                    server = "ssh.example",
                    serverPort = 22,
                    sshAuthType = RelaySshAuthTypePrivateKey,
                    sshHostKeyFingerprint = fingerprint,
                    sshStrictHostKey = true,
                )
            val resolved =
                resolver(TestRelayProfileStore(), TestRelayCredentialStore()).resolveTransient(
                    profile,
                    RelayCredentialRecord(
                        profileId = profile.id,
                        sshUsername = "user",
                        sshPrivateKey = credentialFixture("ssh-key"),
                    ),
                    OwnedRelayQuicMigrationConfig(),
                    TlsFingerprintProfileChromeStable,
                    emptyMap(),
                )
            assertEquals(RelaySshAuthTypePrivateKey, resolved.sshAuthType)
            assertEquals(fingerprint, resolved.sshHostKeyFingerprint)
            assertTrue(resolved.sshStrictHostKey)
        }

    @Test
    fun `transient mieru resolution keeps the profile transport settings`() =
        runTest {
            val profile =
                RelayProfileRecord(
                    id = "mieru-candidate",
                    kind = RelayKindMieru,
                    server = "mieru.example",
                    serverPort = 2999,
                    mieruProtocol = RelayMieruProtocolUdp,
                    mieruMultiplexing = RelayMieruMultiplexingHigh,
                    mieruMtu = 1300,
                )
            val resolved =
                resolver(TestRelayProfileStore(), TestRelayCredentialStore()).resolveTransient(
                    profile,
                    RelayCredentialRecord(
                        profileId = profile.id,
                        mieruUsername = "user",
                        mieruPassword = credentialFixture("mieru"),
                    ),
                    OwnedRelayQuicMigrationConfig(),
                    TlsFingerprintProfileChromeStable,
                    emptyMap(),
                )
            assertEquals(RelayMieruProtocolUdp, resolved.mieruProtocol)
            assertEquals(RelayMieruMultiplexingHigh, resolved.mieruMultiplexing)
            assertEquals(1300, resolved.mieruMtu)
        }

    @Test
    fun `apps script fields left on a non apps script profile stay off the wire`() =
        runTest {
            val profile =
                RelayProfileRecord(
                    id = "trojan-after-apps-script",
                    kind = RelayKindTrojan,
                    server = "trojan.example",
                    serverPort = 443,
                    serverName = "trojan.example",
                    appsScriptScriptIds = listOf("script-id"),
                    appsScriptGoogleIp = "203.0.113.7",
                    appsScriptFrontDomain = "www.example",
                    appsScriptSniHosts = listOf("sni.example"),
                    appsScriptParallelRelay = true,
                    appsScriptDirectHosts = listOf("direct.example"),
                )
            val resolved =
                resolver(TestRelayProfileStore(), TestRelayCredentialStore()).resolveTransient(
                    profile,
                    RelayCredentialRecord(profileId = profile.id, trojanPassword = credentialFixture("trojan")),
                    OwnedRelayQuicMigrationConfig(),
                    TlsFingerprintProfileChromeStable,
                    emptyMap(),
                )
            assertEquals(emptyList<String>(), resolved.appsScriptScriptIds)
            assertEquals("", resolved.appsScriptGoogleIp)
            assertEquals("", resolved.appsScriptFrontDomain)
            assertEquals(emptyList<String>(), resolved.appsScriptSniHosts)
            assertFalse(resolved.appsScriptParallelRelay)
            assertEquals(emptyList<String>(), resolved.appsScriptDirectHosts)
        }
}
