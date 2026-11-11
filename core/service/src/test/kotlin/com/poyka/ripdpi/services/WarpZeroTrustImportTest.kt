package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationOutcome
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.service.warp.DefaultWarpRuntimeConfigResolver
import com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpZeroTrustImportTest {
    @Test
    fun `invalid import material changes no saved state`() =
        runTest {
            val valid = sampleZeroTrustImportRequest()
            val endpoint = checkNotNull(valid.endpoint)
            val invalid =
                listOf(
                    valid.copy(privateKey = null),
                    valid.copy(publicKey = "not-a-key"),
                    valid.copy(peerPublicKey = "AA=="),
                    valid.copy(interfaceAddressV4 = "not-an-ip"),
                    valid.copy(interfaceAddressV6 = "fd00::2/129"),
                    valid.copy(endpoint = null),
                    valid.copy(endpoint = endpoint.copy(port = 0)),
                    valid.copy(endpoint = endpoint.copy(ipv4 = "999.1.2.3")),
                    valid.copy(endpoint = endpoint.copy(ipv6 = "not-an-ip")),
                    valid.copy(endpoint = endpoint.copy(host = "bad host")),
                    valid.copy(endpoint = endpoint.copy(host = "example.com..")),
                )
            invalid.forEach { request ->
                val fixture = Fixture()
                val error = runCatching { fixture.importService().importZeroTrustProfile(request) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertTrue(fixture.profiles.loadAll().isEmpty())
                assertTrue(fixture.credentials.loadAll().isEmpty())
                assertTrue(fixture.endpoints.loadAll(request.profileId).isEmpty())
                assertEquals(null, fixture.profiles.activeProfileId())
            }
        }

    @Test
    fun `complete import replaces endpoints from a reused profile id`() =
        runTest {
            val fixture = Fixture()
            val request = sampleZeroTrustImportRequest()
            fixture.endpoints.save(
                checkNotNull(
                    request.endpoint,
                ).copy(profileId = request.profileId, networkScopeKey = "old-network", port = 1),
            )

            val saved = fixture.importService().importZeroTrustProfile(request)

            assertEquals(request.profileId, fixture.profiles.activeProfileId())
            assertEquals(saved.endpoint, fixture.endpoints.loadAll(request.profileId).single())
            assertEquals(GlobalWarpEndpointScopeKey, saved.endpoint?.networkScopeKey)
            assertEquals("zero_trust_import", saved.endpoint?.source)
            assertEquals(2408, saved.endpoint?.port)
        }

    @Test
    fun `complete import accepts a single DNS root dot`() =
        runTest {
            val fixture = Fixture()
            val request = sampleZeroTrustImportRequest()
            val endpoint = checkNotNull(request.endpoint).copy(host = "example.com.")

            val saved = fixture.importService().importZeroTrustProfile(request.copy(endpoint = endpoint))

            assertEquals("example.com.", saved.endpoint?.host)
            assertEquals(saved.endpoint, fixture.endpoints.loadAll(request.profileId).single())
        }

    @Test
    fun `new complete import resolves its endpoint without consumer refresh`() =
        runTest {
            val fixture = Fixture()
            val imports = fixture.importService()
            val saved = imports.importZeroTrustProfile(sampleZeroTrustImportRequest())
            val orchestrator = DefaultWarpEnrollmentOrchestrator(fixture.flow(fixture.mutations), imports)
            val resolver =
                DefaultWarpRuntimeConfigResolver(
                    fixture.credentials,
                    fixture.endpoints,
                    orchestrator,
                    fixture.mutations,
                )

            val resolved =
                resolver.resolve(
                    RipDpiWarpConfig(enabled = true),
                    RequestedWarpRuntimeReference(
                        saved.profile.id,
                        fixture.mutations.warpRuntimeRevision(saved.profile.id),
                    ),
                )

            assertEquals(saved.profile.id, resolved.configuration.profileId)
            assertEquals(saved.endpoint?.host, resolved.configuration.endpoint.host)
            assertEquals(saved.endpoint?.port, resolved.configuration.endpoint.port)
        }

    @Test
    fun `superseded registration and import cannot return successful snapshots`() =
        runTest {
            listOf(false, true).forEach { register ->
                val fixture = Fixture()
                val authority =
                    com.poyka.ripdpi.data
                        .testPauseAuthority()
                val mutations =
                    object : ProfileMutationCoordinator by fixture.mutations {
                        override suspend fun captureMutation(origin: ProfileMutationOrigin) =
                            ProfileMutationPreparation(origin, authority.reference())

                        override suspend fun upsertWarp(
                            preparation: ProfileMutationPreparation,
                            profile: WarpProfile,
                            credentials: WarpCredentials,
                            endpoints: List<WarpEndpointCacheEntry>,
                            activate: Boolean,
                            scannerMode: String,
                        ): ProfileMutationOutcome {
                            val outcome =
                                authority.invalidateForMutation(
                                    preparation.origin,
                                    "late-warp",
                                    preparation.expectedPauseAuthority,
                                )
                            if (outcome != ProfileMutationOutcome.Superseded) {
                                fixture.mutations.upsertWarp(
                                    preparation,
                                    profile,
                                    credentials,
                                    endpoints,
                                    activate,
                                    scannerMode,
                                )
                            }
                            return outcome
                        }
                    }
                fixture.lock.mutex.lock()
                val operation =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            if (register) {
                                fixture.flow(mutations).registerConsumerFree(
                                    "Consumer",
                                    WarpRegisterDeviceRequest(publicKey = "fixture-key"),
                                    "corp",
                                    null,
                                )
                            } else {
                                fixture.importService(mutations).importZeroTrustProfile(sampleZeroTrustImportRequest())
                            }
                        }
                    }
                assertFalse(operation.isCompleted)
                authority.reserveStop()
                val stopped = authority.snapshotAuthority()
                fixture.lock.mutex.unlock()

                assertTrue(operation.await().exceptionOrNull() is IllegalStateException)
                assertTrue(fixture.profiles.loadAll().isEmpty())
                assertTrue(fixture.credentials.loadAll().isEmpty())
                assertTrue(fixture.endpoints.loadAll("corp").isEmpty())
                assertEquals(stopped, authority.snapshotAuthority())
            }
        }

    private class Fixture {
        val settings = TestAppSettingsRepository()
        val profiles = FakeWarpProfileStore()
        val credentials = FakeWarpCredentialStore()
        val endpoints = FakeWarpEndpointStore()
        val lock = WarpStoreMutationLock()
        val mutations = TestProfileMutationCoordinator(settings, profiles, credentials, endpoints)

        fun importService(owner: ProfileMutationCoordinator = mutations) =
            DefaultWarpCredentialProfileMutationService(profiles, credentials, endpoints, lock, owner)

        fun flow(owner: ProfileMutationCoordinator) =
            DefaultWarpEnrollmentFlowService(
                settings,
                profiles,
                credentials,
                endpoints,
                FakeWarpProvisioningClient(sampleWarpProvisioningResult()),
                PassthroughWarpBootstrapProxyRunner(),
                DefaultWarpEndpointScanner(settings, endpoints, FakeWarpEndpointProbe()),
                DefaultWarpProfileActivationService(profiles, credentials, endpoints, owner, lock),
                lock,
                owner,
            )
    }
}
