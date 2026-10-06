package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpSetupStateProvisioned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarpRuntimeProvisioningFlowTest {
    @Test fun `stale runtime authentication failure leaves current user profile metadata unchanged`() =
        runTest {
            val profile =
                WarpProfile(id = DefaultWarpProfileId, displayName = "Current", setupState = WarpSetupStateProvisioned)
            val oldCredentials = sampleWarpProvisioningResult().credentials
            val settings =
                TestAppSettingsRepository(
                    com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWarpProfileId(
                            profile.id,
                        ).build(),
                )
            val profiles =
                FakeWarpProfileStore().apply {
                    save(profile)
                    setActiveProfileId(profile.id)
                }
            val credentials = FakeWarpCredentialStore().apply { save(profile.id, oldCredentials) }
            val endpoints = FakeWarpEndpointStore()
            val mutations = TestProfileMutationCoordinator(settings, profiles, credentials, endpoints)
            val lock = WarpStoreMutationLock()
            val entered = CompletableDeferred<Unit>()
            val completion = CompletableDeferred<WarpProvisioningResult>()
            val client =
                object : WarpProvisioningClient {
                    override suspend fun register(
                        request: WarpRegisterDeviceRequest,
                        bootstrapProxy: java.net.Proxy?,
                    ) = sampleWarpProvisioningResult()

                    override suspend fun refresh(
                        credentials: WarpCredentials,
                        bootstrapProxy: java.net.Proxy?,
                    ): WarpProvisioningResult {
                        entered.complete(Unit)
                        return completion.await()
                    }
                }
            val flow =
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
                )
            val refreshing =
                async { runCatching { flow.refreshProfileForRuntime(profile.id, oldCredentials, 0, "wifi:home") } }
            entered.await()
            val currentCredentials = oldCredentials.copy(privateKey = "current-user-key")
            val currentProfile = profile.copy(displayName = "User renamed")
            mutations.upsertWarp(
                mutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                currentProfile,
                currentCredentials,
                emptyList(),
                false,
                WarpScannerModeAutomatic,
            )
            val currentSettings = settings.snapshot()
            completion.completeExceptionally(WarpProvisioningException.AuthFailure("old request rejected"))
            assertTrue(refreshing.await().isFailure)
            assertEquals(currentProfile, profiles.load(profile.id))
            assertEquals(currentCredentials, credentials.load(profile.id))
            assertEquals(currentSettings, settings.snapshot())
        }

    @Test
    fun `runtime provisioning rejection preserves endpoint after scanner proposal and user ABA`() =
        runTest {
            val profile = WarpProfile(id = DefaultWarpProfileId, displayName = "Current")
            val originalCredentials = sampleWarpProvisioningResult().credentials
            val originalEndpoint =
                WarpEndpointCacheEntry(
                    profileId = profile.id,
                    networkScopeKey = "wifi:home",
                    host = "old.example",
                    ipv4 = "192.0.2.10",
                    port = 2408,
                    source = "saved",
                    updatedAtEpochMillis = 1L,
                )
            val settings =
                TestAppSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWarpProfileId(profile.id)
                        .build(),
                )
            val profiles =
                FakeWarpProfileStore().apply {
                    save(profile)
                    setActiveProfileId(profile.id)
                }
            val credentials = FakeWarpCredentialStore().apply { save(profile.id, originalCredentials) }
            val endpoints = FakeWarpEndpointStore().apply { save(originalEndpoint) }
            val mutations = TestProfileMutationCoordinator(settings, profiles, credentials, endpoints)
            val lock = WarpStoreMutationLock()
            var changed = false
            val scanner =
                DefaultWarpEndpointScanner(
                    settings,
                    endpoints,
                    object : WarpEndpointProbe {
                        override suspend fun probe(
                            candidate: WarpEndpointCacheEntry,
                            timeoutMillis: Int,
                        ): WarpEndpointCacheEntry? {
                            if (!changed) {
                                changed = true
                                mutations.upsertWarp(
                                    mutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                                    profile,
                                    originalCredentials.copy(privateKey = "user-edited-key"),
                                    listOf(originalEndpoint),
                                    false,
                                    WarpScannerModeAutomatic,
                                )
                                mutations.upsertWarp(
                                    mutations.captureMutation(com.poyka.ripdpi.data.ProfileMutationOrigin.SavedEdit),
                                    profile,
                                    originalCredentials,
                                    listOf(originalEndpoint),
                                    false,
                                    WarpScannerModeAutomatic,
                                )
                            }
                            return if (candidate.ipv4 == originalEndpoint.ipv4) null else candidate.copy(rttMs = 1)
                        }
                    },
                )
            val flow =
                DefaultWarpEnrollmentFlowService(
                    settings,
                    profiles,
                    credentials,
                    endpoints,
                    FakeWarpProvisioningClient(sampleWarpProvisioningResult()),
                    PassthroughWarpBootstrapProxyRunner(),
                    scanner,
                    DefaultWarpProfileActivationService(profiles, credentials, endpoints, mutations, lock),
                    lock,
                    mutations,
                )
            val revision = mutations.warpRuntimeRevision(profile.id)
            val failure =
                runCatching {
                    flow.refreshProfileForRuntime(profile.id, originalCredentials, revision, "wifi:home")
                }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(changed)
            assertEquals(listOf(originalEndpoint), endpoints.loadAll(profile.id))
            assertEquals(originalCredentials, credentials.load(profile.id))
            assertEquals(profile, profiles.load(profile.id))
        }
}
