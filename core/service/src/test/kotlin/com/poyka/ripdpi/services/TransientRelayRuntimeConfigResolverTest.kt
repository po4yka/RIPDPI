package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
