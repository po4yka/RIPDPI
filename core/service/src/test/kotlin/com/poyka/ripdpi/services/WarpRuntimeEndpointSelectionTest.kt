package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.GlobalWarpEndpointScopeKey
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.service.warp.DefaultWarpRuntimeConfigResolver
import com.poyka.ripdpi.service.warp.RequestedWarpRuntimeReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

class WarpRuntimeEndpointSelectionTest {
    @Test
    fun `reconnect checks fresh scoped cache and selects authenticated alternate without HTTP refresh`() =
        runTest {
            val fixture = Fixture()
            val dead = fixture.endpoint("127.0.0.1", "wifi:current")
            val healthy = fixture.endpoint("127.0.0.2", GlobalWarpEndpointScopeKey)
            fixture.endpoints.save(dead)
            fixture.endpoints.save(healthy)
            val resolved =
                fixture.resolver(FakeWarpEndpointProbe(mapOf("127.0.0.2" to 3L))).resolve(
                    RipDpiWarpConfig(enabled = true),
                    fixture.reference(),
                    "wifi:current",
                    RelayRuntimeNetworkMode.Proxy,
                )
            assertEquals("127.0.0.2", resolved.configuration.endpoint.ipv4)
            assertEquals("127.0.0.2", fixture.endpoints.load(fixture.profile.id, "wifi:current")?.ipv4)
            assertNull(resolved.requestedPatch)
        }

    @Test
    fun `runtime cannot fall back to unprobed provisioning endpoint after all probes fail`() =
        runTest {
            val fixture = Fixture()
            val saved = fixture.endpoint("127.0.0.1", "wifi:current")
            fixture.endpoints.save(saved)
            val failure =
                runCatching {
                    fixture.resolver(FakeWarpEndpointProbe()).resolve(
                        RipDpiWarpConfig(enabled = true),
                        fixture.reference(),
                        "wifi:current",
                        RelayRuntimeNetworkMode.Proxy,
                    )
                }.exceptionOrNull()
            assertTrue(failure is ServiceStartupRejectedException)
            assertEquals(saved, fixture.endpoints.load(fixture.profile.id, "wifi:current"))
        }

    @Test
    fun `missing cached endpoint scans without consumer API and Zero Trust never refreshes`() =
        runTest {
            val fixture = Fixture()
            val resolved =
                fixture.resolver(FakeWarpEndpointProbe(mapOf("162.159.192.1:2408" to 1L))).resolve(
                    RipDpiWarpConfig(enabled = true),
                    fixture.reference(),
                    "wifi:current",
                    RelayRuntimeNetworkMode.Proxy,
                )
            assertEquals("162.159.192.1", resolved.configuration.endpoint.ipv4)
            fixture.credentials.save(
                fixture.profile.id,
                fixture.material.copy(
                    accountKind = com.poyka.ripdpi.data.WarpAccountKindZeroTrust,
                    peerPublicKey = null,
                ),
            )
            assertTrue(
                runCatching {
                    fixture
                        .resolver(
                            FakeWarpEndpointProbe(),
                        ).resolve(RipDpiWarpConfig(enabled = true), fixture.reference())
                }.exceptionOrNull()?.message.orEmpty().contains("complete local import"),
            )
        }

    @Test
    fun `manual endpoint does not scan and missing local keys cannot trigger provider refresh`() =
        runTest {
            val fixture = Fixture()
            val rejectingProbe =
                object : WarpEndpointProbe {
                    override suspend fun probe(
                        candidate: WarpEndpointCacheEntry,
                        timeoutMillis: Int,
                    ): WarpEndpointCacheEntry? = error("manual endpoint must not scan")
                }
            val config =
                RipDpiWarpConfig(
                    enabled = true,
                    endpointSelectionMode = "manual",
                    manualEndpoint =
                        com.poyka.ripdpi.core
                            .RipDpiWarpManualEndpointConfig(host = "127.0.0.1", port = 2408),
                )
            assertEquals(
                "manual",
                fixture
                    .resolver(rejectingProbe)
                    .resolve(config, fixture.reference())
                    .configuration.endpoint.source,
            )
            fixture.credentials.save(fixture.profile.id, fixture.material.copy(privateKey = null))
            assertTrue(runCatching { fixture.resolver(rejectingProbe).resolve(config, fixture.reference()) }.isFailure)
        }

    @Test
    fun `runtime endpoint proposal cannot overwrite concurrent user credentials`() =
        runTest {
            val fixture = Fixture()
            val saved = fixture.endpoint("127.0.0.1", "wifi:current")
            fixture.endpoints.save(saved)
            val updated = fixture.material.copy(privateKey = "user-key")
            val probe =
                object : WarpEndpointProbe {
                    override suspend fun probe(
                        candidate: WarpEndpointCacheEntry,
                        timeoutMillis: Int,
                    ): WarpEndpointCacheEntry {
                        fixture.mutations.upsertWarp(
                            fixture.mutations.captureMutation(ProfileMutationOrigin.SavedEdit),
                            fixture.profile,
                            updated,
                            listOf(saved),
                            false,
                            WarpScannerModeAutomatic,
                        )
                        return candidate.copy(ipv4 = "127.0.0.2", rttMs = 1)
                    }
                }
            assertTrue(
                runCatching {
                    fixture
                        .resolver(
                            probe,
                        ).resolve(RipDpiWarpConfig(enabled = true), fixture.reference(), "wifi:current")
                }.isFailure,
            )
            assertEquals(updated, fixture.credentials.load(fixture.profile.id))
            assertEquals(saved, fixture.endpoints.load(fixture.profile.id, "wifi:current"))
        }

    @Test
    fun `scanner limits concurrency and timeout from frozen runtime configuration`() =
        runTest {
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val fixture = Fixture()
            val probe =
                object : WarpEndpointProbe {
                    override suspend fun probe(
                        candidate: WarpEndpointCacheEntry,
                        timeoutMillis: Int,
                    ): WarpEndpointCacheEntry? {
                        check(timeoutMillis == 10_000)
                        val count = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, count) }
                        delay(1)
                        active.decrementAndGet()
                        return null
                    }
                }
            val context =
                WarpEndpointScanContext(
                    fixture.material,
                    RipDpiWarpConfig(scannerParallelism = Int.MAX_VALUE, scannerMaxRttMs = Int.MAX_VALUE),
                )
            assertNull(
                DefaultWarpEndpointScanner(fixture.settings, fixture.endpoints, probe).resolveEndpoint(
                    fixture.profile.id,
                    "wifi:current",
                    null,
                    WarpEndpointResolutionOrigin.RuntimeProvisioning,
                    context,
                ),
            )
            assertEquals(64, maximum.get())
            assertEquals(0, active.get())
            assertFalse(context.toString().contains(fixture.material.privateKey.orEmpty()))
        }

    private class Fixture {
        val profile = WarpProfile(id = DefaultWarpProfileId, displayName = "Saved")
        val material =
            sampleWarpProvisioningResult().let {
                it.credentials.copy(
                    peerPublicKey = it.peerPublicKey,
                    interfaceAddressV4 = it.interfaceAddressV4,
                )
            }
        val settings =
            TestAppSettingsRepository(
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setWarpProfileId(profile.id)
                    .setWarpScannerEnabled(true)
                    .build(),
            )
        val profiles = FakeWarpProfileStore()
        val credentials = FakeWarpCredentialStore()
        val endpoints = FakeWarpEndpointStore()
        val mutations = TestProfileMutationCoordinator(settings, profiles, credentials, endpoints)
        private val noHttp =
            object : WarpProvisioningClient {
                override suspend fun register(
                    request: WarpRegisterDeviceRequest,
                    bootstrapProxy: Proxy?,
                ): WarpProvisioningResult = error("unexpected consumer registration")

                override suspend fun refresh(
                    credentials: WarpCredentials,
                    bootstrapProxy: Proxy?,
                ): WarpProvisioningResult = error("unexpected consumer refresh")
            }

        suspend fun reference(): RequestedWarpRuntimeReference {
            if (profiles.load(profile.id) == null) {
                profiles.save(profile)
                credentials.save(profile.id, material)
            }
            return RequestedWarpRuntimeReference(profile.id, mutations.warpRuntimeRevision(profile.id))
        }

        fun endpoint(
            ip: String,
            scope: String,
        ) = WarpEndpointCacheEntry(
            profileId = profile.id,
            networkScopeKey = scope,
            ipv4 = ip,
            port = 2408,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )

        fun resolver(probe: WarpEndpointProbe): DefaultWarpRuntimeConfigResolver {
            val scanner = DefaultWarpEndpointScanner(settings, endpoints, probe)
            val lock = WarpStoreMutationLock()
            val activation = DefaultWarpProfileActivationService(profiles, credentials, endpoints, mutations, lock)
            val flow =
                DefaultWarpEnrollmentFlowService(
                    settings,
                    profiles,
                    credentials,
                    endpoints,
                    noHttp,
                    PassthroughWarpBootstrapProxyRunner(),
                    scanner,
                    activation,
                    lock,
                    mutations,
                )
            val imports = DefaultWarpCredentialProfileMutationService(profiles, credentials, endpoints, lock, mutations)
            return DefaultWarpRuntimeConfigResolver(
                credentials,
                endpoints,
                DefaultWarpEnrollmentOrchestrator(flow, imports),
                mutations,
                profiles,
                scanner,
            )
        }
    }
}
