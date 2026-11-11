package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.WarpKeyPair
import com.poyka.ripdpi.core.WarpKeyPairGenerator
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpSetupStateProvisioned
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WarpProfileSetupServiceTest {
    @Test
    fun `explicit registration saves generated keys under a new profile id each time`() =
        runTest {
            val fixture = Fixture()

            fixture.setup.registerConsumerFree("First")
            val firstId = fixture.profiles.activeProfileId()
            fixture.setup.registerConsumerFree("Second")
            val secondId = fixture.profiles.activeProfileId()

            assertNotEquals(firstId, secondId)
            assertEquals(
                setOf("First", "Second"),
                fixture.profiles
                    .loadAll()
                    .map(WarpProfile::displayName)
                    .toSet(),
            )
            assertEquals(PrivateKey, fixture.credentials.load(checkNotNull(firstId))?.privateKey)
            assertEquals(PublicKey, fixture.credentials.load(checkNotNull(secondId))?.publicKey)
            assertEquals(secondId, fixture.settings.snapshot().warpProfileId)
        }

    @Test
    fun `entropy failure preserves existing profile and credentials`() =
        runTest {
            val fixture = Fixture(generatorFailure = IOException("entropy unavailable"))
            val existing = WarpProfile(id = "saved", displayName = "Saved", setupState = WarpSetupStateProvisioned)
            val secret =
                WarpCredentials(profileId = existing.id, deviceId = "saved-device", accessToken = "fixture-saved-token")
            fixture.profiles.save(existing)
            fixture.profiles.setActiveProfileId(existing.id)
            fixture.credentials.save(existing.id, secret)

            val failure = runCatching { fixture.setup.registerConsumerFree("New") }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(listOf(existing), fixture.profiles.loadAll())
            assertEquals(secret, fixture.credentials.load(existing.id))
        }

    @Test
    fun `complete document persists the imported endpoint and private material`() =
        runTest {
            val fixture = Fixture()

            fixture.setup.importZeroTrustProfile(completeDocument())

            val id = checkNotNull(fixture.profiles.activeProfileId())
            assertEquals("Work", fixture.profiles.load(id)?.displayName)
            assertEquals(PrivateKey, fixture.credentials.load(id)?.privateKey)
            val endpoint = fixture.endpoints.load(id, GlobalWarpEndpointScopeKey)
            assertEquals("127.0.0.1", endpoint?.ipv4)
            assertEquals(2408, endpoint?.port)
            assertEquals("zero_trust_import", endpoint?.source)
        }

    @Test
    fun `unsupported oversized and malformed documents leave stores unchanged and redact input`() =
        runTest {
            val fixture = Fixture()
            val invalid =
                listOf(
                    completeDocument().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                    " ".repeat(64 * 1024 + 1),
                    """{"privateKey":"synthetic-document-secret","schemaVersion":"bad"}""",
                )

            invalid.forEach { document ->
                val failure = runCatching { fixture.setup.importZeroTrustProfile(document) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertFalse(failure.toString().contains("synthetic-document-secret"))
                assertTrue(fixture.profiles.loadAll().isEmpty())
                assertTrue(fixture.credentials.loadAll().isEmpty())
            }
        }

    private class Fixture(
        generatorFailure: Exception? = null,
    ) {
        val settings =
            TestAppSettingsRepository(
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setWarpScannerEnabled(false)
                    .build(),
            )
        val profiles = FakeWarpProfileStore()
        val credentials = FakeWarpCredentialStore()
        val endpoints = FakeWarpEndpointStore()
        private val mutations = TestProfileMutationCoordinator(settings, profiles, credentials, endpoints)
        private val lock = WarpStoreMutationLock()
        private val client =
            object : WarpProvisioningClient {
                override suspend fun register(
                    request: WarpRegisterDeviceRequest,
                    bootstrapProxy: java.net.Proxy?,
                ): WarpProvisioningResult =
                    sampleWarpProvisioningResult().let { result ->
                        result.copy(
                            credentials =
                                result.credentials.copy(privateKey = request.privateKey, publicKey = request.publicKey),
                        )
                    }

                override suspend fun refresh(
                    credentials: WarpCredentials,
                    bootstrapProxy: java.net.Proxy?,
                ): WarpProvisioningResult = error("Consumer refresh is not part of ready-profile import")
            }
        private val orchestrator =
            DefaultWarpEnrollmentOrchestrator(
                DefaultWarpEnrollmentFlowService(
                    settings,
                    profiles,
                    credentials,
                    endpoints,
                    client,
                    PassthroughWarpBootstrapProxyRunner(),
                    DefaultWarpEndpointScanner(settings, endpoints, FakeWarpEndpointProbe()),
                    DefaultWarpProfileActivationService(profiles, credentials, endpoints, mutations, lock),
                    lock,
                    mutations,
                ),
                DefaultWarpCredentialProfileMutationService(profiles, credentials, endpoints, lock, mutations),
            )
        val setup =
            DefaultWarpProfileSetupService(
                orchestrator,
                object : WarpKeyPairGenerator {
                    override suspend fun generate(): WarpKeyPair {
                        generatorFailure?.let { throw it }
                        return WarpKeyPair(PrivateKey, PublicKey)
                    }
                },
            )
    }

    private fun completeDocument(): String =
        """
        {
          "schemaVersion": 1,
          "displayName": "Work",
          "organization": "audit-team",
          "deviceId": "synthetic-device",
          "accessToken": "synthetic-access-token",
          "privateKey": "$PrivateKey",
          "publicKey": "$PublicKey",
          "peerPublicKey": "$PeerPublicKey",
          "interfaceAddressV4": "172.16.0.2/32",
          "endpoint": {"host": "127.0.0.1", "ipv4": "127.0.0.1", "port": 2408}
        }
        """.trimIndent()

    private companion object {
        // RFC 7748 section 6.1; these published test-vector keys are not real credentials.
        const val PrivateKey = "dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo="
        const val PublicKey = "hSDwCYkwp1R0i33ctD73Wg2/Og0mOBr066SpjqqbTmo="
        const val PeerPublicKey = "3p7bfXt9wbTTW2HC7OQ1Nz+DQ8hbeGda1/x+FG+IK08="
    }
}
