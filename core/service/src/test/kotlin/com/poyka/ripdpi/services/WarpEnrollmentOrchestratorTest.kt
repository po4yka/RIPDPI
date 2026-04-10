package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.WarpAccountKindConsumerFree
import com.poyka.ripdpi.data.WarpAccountKindConsumerPlus
import com.poyka.ripdpi.data.WarpAccountKindZeroTrust
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfile
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.WarpScannerModeAutomatic
import com.poyka.ripdpi.data.WarpScannerModeManual
import com.poyka.ripdpi.data.WarpSetupStateNeedsAttention
import com.poyka.ripdpi.data.WarpSetupStateNotConfigured
import com.poyka.ripdpi.data.WarpSetupStateProvisioned
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WarpEnrollmentOrchestratorTest {
    @Test
    fun `consumer registration stores profile credentials endpoint and app metadata`() =
        runTest {
            val consumerAccessValue = fixtureAccessValue("consumer")
            val appSettingsRepository = TestAppSettingsRepository()
            val profileStore = FakeWarpProfileStore()
            val credentialStore = FakeWarpCredentialStore()
            val endpointStore = FakeWarpEndpointStore()
            val provisioningClient =
                FakeWarpProvisioningClient(
                    registerResult =
                        sampleProvisioningResult().copy(
                            accountType = "free",
                            warpPlus = false,
                            license = null,
                        ),
                )
            val orchestrator =
                DefaultWarpEnrollmentOrchestrator(
                    appSettingsRepository = appSettingsRepository,
                    profileStore = profileStore,
                    credentialStore = credentialStore,
                    endpointStore = endpointStore,
                    provisioningClient = provisioningClient,
                    bootstrapProxyRunner = PassthroughWarpBootstrapProxyRunner(),
                    endpointScanner = DefaultWarpEndpointScanner(endpointStore),
                )

            val snapshot =
                orchestrator.registerConsumerFree(
                    displayName = "Home WARP",
                    request = WarpRegisterDeviceRequest(publicKey = "pub", privateKey = "priv"),
                    profileId = "home-warp",
                    networkScopeKey = "wifi:home",
                )

            assertEquals("home-warp", snapshot.profile.id)
            assertEquals(WarpAccountKindConsumerFree, snapshot.profile.accountKind)
            assertEquals("home-warp", appSettingsRepository.snapshot().warpProfileId)
            assertEquals(WarpAccountKindConsumerFree, appSettingsRepository.snapshot().warpAccountKind)
            assertEquals(WarpSetupStateProvisioned, appSettingsRepository.snapshot().warpSetupState)
            assertEquals(WarpScannerModeAutomatic, appSettingsRepository.snapshot().warpLastScannerMode)
            assertEquals("home-warp", credentialStore.load("home-warp")?.profileId)
            assertEquals("wifi:home", endpointStore.load("home-warp", "wifi:home")?.networkScopeKey)
        }

    @Test
    fun `warp plus attach and remove mutate active profile kind`() =
        runTest {
            val consumerAccessValue = fixtureAccessValue("consumer")
            val appSettingsRepository = TestAppSettingsRepository()
            val profileStore = FakeWarpProfileStore()
            val credentialStore = FakeWarpCredentialStore()
            val endpointStore = FakeWarpEndpointStore()
            profileStore.save(
                WarpProfile(
                    id = DefaultWarpProfileId,
                    accountKind = WarpAccountKindConsumerFree,
                    displayName = "Consumer",
                    setupState = WarpSetupStateProvisioned,
                ),
            )
            profileStore.setActiveProfileId(DefaultWarpProfileId)
            credentialStore.save(
                DefaultWarpProfileId,
                WarpCredentials(
                    profileId = DefaultWarpProfileId,
                    deviceId = "device-1",
                    accessToken = consumerAccessValue,
                ),
            )
            val orchestrator =
                DefaultWarpEnrollmentOrchestrator(
                    appSettingsRepository = appSettingsRepository,
                    profileStore = profileStore,
                    credentialStore = credentialStore,
                    endpointStore = endpointStore,
                    provisioningClient = FakeWarpProvisioningClient(registerResult = sampleProvisioningResult()),
                    bootstrapProxyRunner = PassthroughWarpBootstrapProxyRunner(),
                    endpointScanner = DefaultWarpEndpointScanner(endpointStore),
                )

            orchestrator.attachWarpPlusLicense(DefaultWarpProfileId, "license-123")
            assertEquals(WarpAccountKindConsumerPlus, profileStore.load(DefaultWarpProfileId)?.accountKind)
            assertEquals("license-123", credentialStore.load(DefaultWarpProfileId)?.license)

            orchestrator.removeWarpPlusLicense(DefaultWarpProfileId)
            assertEquals(WarpAccountKindConsumerFree, profileStore.load(DefaultWarpProfileId)?.accountKind)
            assertNull(credentialStore.load(DefaultWarpProfileId)?.license)
        }

    @Test
    fun `zero trust import stores organization and activates profile`() =
        runTest {
            val zeroTrustAccessValue = fixtureAccessValue("corp")
            val appSettingsRepository = TestAppSettingsRepository()
            val profileStore = FakeWarpProfileStore()
            val credentialStore = FakeWarpCredentialStore()
            val endpointStore = FakeWarpEndpointStore()
            val orchestrator =
                DefaultWarpEnrollmentOrchestrator(
                    appSettingsRepository = appSettingsRepository,
                    profileStore = profileStore,
                    credentialStore = credentialStore,
                    endpointStore = endpointStore,
                    provisioningClient = FakeWarpProvisioningClient(registerResult = sampleProvisioningResult()),
                    bootstrapProxyRunner = PassthroughWarpBootstrapProxyRunner(),
                    endpointScanner = DefaultWarpEndpointScanner(endpointStore),
                )

            val snapshot =
                orchestrator.importZeroTrustProfile(
                    WarpZeroTrustImportRequest(
                        profileId = "corp",
                        displayName = "Corp",
                        organization = "acme",
                        deviceId = "device-corp",
                        accessToken = zeroTrustAccessValue,
                    ),
                )

            assertEquals(WarpAccountKindZeroTrust, snapshot.profile.accountKind)
            assertEquals("acme", snapshot.profile.zeroTrustOrg)
            assertEquals("corp", appSettingsRepository.snapshot().warpProfileId)
            assertEquals(WarpAccountKindZeroTrust, appSettingsRepository.snapshot().warpAccountKind)
            assertEquals("acme", appSettingsRepository.snapshot().warpZeroTrustOrg)
            assertEquals(WarpScannerModeManual, appSettingsRepository.snapshot().warpLastScannerMode)
        }

    @Test
    fun `reset clears active profile metadata and stored records`() =
        runTest {
            val resetAccessValue = fixtureAccessValue("reset")
            val appSettingsRepository =
                TestAppSettingsRepository(
                    initial =
                        com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setWarpProfileId("corp")
                            .setWarpAccountKind(WarpAccountKindZeroTrust)
                            .setWarpZeroTrustOrg("acme")
                            .setWarpSetupState(WarpSetupStateProvisioned)
                            .build(),
                )
            val profileStore = FakeWarpProfileStore()
            val credentialStore = FakeWarpCredentialStore()
            val endpointStore = FakeWarpEndpointStore()
            profileStore.save(
                WarpProfile(
                    id = "corp",
                    accountKind = WarpAccountKindZeroTrust,
                    displayName = "Corp",
                    zeroTrustOrg = "acme",
                    setupState = WarpSetupStateProvisioned,
                ),
            )
            profileStore.setActiveProfileId("corp")
            credentialStore.save(
                "corp",
                WarpCredentials(
                    profileId = "corp",
                    deviceId = "device",
                    accessToken = resetAccessValue,
                ),
            )
            endpointStore.save(
                WarpEndpointCacheEntry(
                    profileId = "corp",
                    networkScopeKey = "wifi:corp",
                    host = "engage.cloudflareclient.com",
                    port = 2408,
                ),
            )
            val orchestrator =
                DefaultWarpEnrollmentOrchestrator(
                    appSettingsRepository = appSettingsRepository,
                    profileStore = profileStore,
                    credentialStore = credentialStore,
                    endpointStore = endpointStore,
                    provisioningClient = FakeWarpProvisioningClient(registerResult = sampleProvisioningResult()),
                    bootstrapProxyRunner = PassthroughWarpBootstrapProxyRunner(),
                    endpointScanner = DefaultWarpEndpointScanner(endpointStore),
                )

            orchestrator.resetProfile("corp")

            assertNull(profileStore.load("corp"))
            assertNull(credentialStore.load("corp"))
            assertNull(endpointStore.load("corp", "wifi:corp"))
            assertEquals(DefaultWarpProfileId, appSettingsRepository.snapshot().warpProfileId)
            assertEquals(WarpAccountKindConsumerFree, appSettingsRepository.snapshot().warpAccountKind)
            assertEquals("", appSettingsRepository.snapshot().warpZeroTrustOrg)
            assertEquals(WarpSetupStateNotConfigured, appSettingsRepository.snapshot().warpSetupState)
        }

    @Test
    fun `managed bootstrap proxy runner starts transient proxy for provisioning`() =
        runTest {
            val appSettingsRepository = TestAppSettingsRepository()
            val proxyFactory = TestRipDpiProxyFactory()
            val runner =
                ManagedWarpBootstrapProxyRunner(
                    appSettingsRepository = appSettingsRepository,
                    proxyRuntimeSupervisorFactory = DefaultProxyRuntimeSupervisorFactory(proxyFactory),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    scope = backgroundScope,
                )

            var seenProxy: WarpBootstrapProxyConfig? = null
            val result =
                runner.withBootstrapProxy { proxy ->
                    seenProxy = proxy
                    "ok"
                }

            assertEquals("ok", result)
            assertNotNull(seenProxy)
            assertEquals("127.0.0.1", seenProxy?.host)
            assertNotNull(proxyFactory.lastRuntime.lastPreferences)
            val preferences = proxyFactory.lastRuntime.lastPreferences as RipDpiProxyUIPreferences
            assertFalse(preferences.relay.enabled)
            assertFalse(preferences.warp.enabled)
            assertTrue(
                preferences.hosts.entries
                    .orEmpty()
                    .contains("api.cloudflareclient.com"),
            )
            assertEquals(seenProxy?.port, preferences.listen.port)
            assertEquals(1, proxyFactory.lastRuntime.stopCount)
        }

    @Test
    fun `refresh marks active profile needs attention when auth fails and keeps credentials`() =
        runTest {
            val accessValue = fixtureAccessValue("refresh")
            val appSettingsRepository =
                TestAppSettingsRepository(
                    initial =
                        com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                            .toBuilder()
                            .setWarpProfileId(DefaultWarpProfileId)
                            .build(),
                )
            val profileStore = FakeWarpProfileStore()
            val credentialStore = FakeWarpCredentialStore()
            val endpointStore = FakeWarpEndpointStore()
            profileStore.save(
                WarpProfile(
                    id = DefaultWarpProfileId,
                    accountKind = WarpAccountKindConsumerFree,
                    displayName = "Default",
                    setupState = WarpSetupStateProvisioned,
                ),
            )
            profileStore.setActiveProfileId(DefaultWarpProfileId)
            credentialStore.save(
                DefaultWarpProfileId,
                WarpCredentials(
                    profileId = DefaultWarpProfileId,
                    deviceId = "device-1",
                    accessToken = accessValue,
                    privateKey = "private-key",
                    publicKey = "public-key",
                    peerPublicKey = "peer-key",
                ),
            )
            val orchestrator =
                DefaultWarpEnrollmentOrchestrator(
                    appSettingsRepository = appSettingsRepository,
                    profileStore = profileStore,
                    credentialStore = credentialStore,
                    endpointStore = endpointStore,
                    provisioningClient =
                        FakeWarpProvisioningClient(
                            registerResult = sampleProvisioningResult(),
                            refreshError = IOException("HTTP 403"),
                        ),
                    bootstrapProxyRunner = PassthroughWarpBootstrapProxyRunner(),
                    endpointScanner = DefaultWarpEndpointScanner(endpointStore),
                )

            val error =
                runCatching {
                    orchestrator.refreshActiveProfile("wifi:home")
                }.exceptionOrNull()

            assertEquals("IOException", error?.javaClass?.simpleName)
            assertEquals("HTTP 403", error?.message)
            assertEquals(WarpSetupStateNeedsAttention, profileStore.load(DefaultWarpProfileId)?.setupState)
            assertEquals(WarpSetupStateNeedsAttention, appSettingsRepository.snapshot().warpSetupState)
            assertEquals(accessValue, credentialStore.load(DefaultWarpProfileId)?.accessToken)
        }

    private fun sampleProvisioningResult(): WarpProvisioningResult =
        WarpProvisioningResult(
            credentials =
                WarpCredentials(
                    profileId = DefaultWarpProfileId,
                    deviceId = "device-123",
                    accessToken = fixtureAccessValue("provisioning"),
                    privateKey = "private-key",
                    publicKey = "public-key",
                ),
            accountId = "account-123",
            accountType = "free",
            warpPlus = false,
            premiumData = 0L,
            quota = 0L,
            license = null,
            interfaceAddressV4 = "172.16.0.2/32",
            interfaceAddressV6 = "2606:4700:110:8a36::2/128",
            peerPublicKey = "peer-public-key",
            endpoint =
                WarpEndpointCacheEntry(
                    networkScopeKey = "",
                    host = "engage.cloudflareclient.com",
                    ipv4 = "162.159.192.1",
                    port = 2408,
                    source = "registration",
                ),
            reservedBytes = byteArrayOf(1, 2, 3),
        )

    private fun fixtureAccessValue(suffix: String): String = listOf("access", "value", suffix).joinToString("-")
}

private class FakeWarpProvisioningClient(
    private val registerResult: WarpProvisioningResult,
    private val refreshError: Exception? = null,
) : WarpProvisioningClient {
    override suspend fun register(
        request: WarpRegisterDeviceRequest,
        bootstrapProxy: java.net.Proxy?,
    ): WarpProvisioningResult = registerResult

    override suspend fun refresh(
        credentials: WarpCredentials,
        bootstrapProxy: java.net.Proxy?,
    ): WarpProvisioningResult {
        refreshError?.let { throw it }
        return registerResult
    }
}

private class FakeWarpProfileStore : WarpProfileStore {
    private val profiles = linkedMapOf<String, WarpProfile>()
    private var activeProfileId: String? = null

    override suspend fun load(profileId: String): WarpProfile? = profiles[profileId]

    override suspend fun loadAll(): List<WarpProfile> = profiles.values.toList()

    override suspend fun save(profile: WarpProfile) {
        profiles[profile.id] = profile
    }

    override suspend fun remove(profileId: String) {
        profiles.remove(profileId)
    }

    override suspend fun activeProfileId(): String? = activeProfileId

    override suspend fun setActiveProfileId(profileId: String?) {
        activeProfileId = profileId
    }

    override suspend fun clearAll() {
        profiles.clear()
        activeProfileId = null
    }
}

private class FakeWarpCredentialStore : WarpCredentialStore {
    private val credentials = linkedMapOf<String, WarpCredentials>()

    override suspend fun load(profileId: String): WarpCredentials? = credentials[profileId]

    override suspend fun loadAll(): List<WarpCredentials> = credentials.values.toList()

    override suspend fun save(
        profileId: String,
        credentials: WarpCredentials,
    ) {
        this.credentials[profileId] = credentials.copy(profileId = profileId)
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }

    override suspend fun clearAll() {
        credentials.clear()
    }
}

private class FakeWarpEndpointStore : WarpEndpointStore {
    private val entries = linkedMapOf<Pair<String, String>, WarpEndpointCacheEntry>()

    override suspend fun load(
        profileId: String,
        networkScopeKey: String,
    ): WarpEndpointCacheEntry? = entries[profileId to networkScopeKey]

    override suspend fun loadAll(profileId: String): List<WarpEndpointCacheEntry> =
        entries.filterKeys { (entryProfileId, _) -> entryProfileId == profileId }.values.toList()

    override suspend fun save(entry: WarpEndpointCacheEntry) {
        entries[entry.profileId to entry.networkScopeKey] = entry
    }

    override suspend fun clear(
        profileId: String,
        networkScopeKey: String,
    ) {
        entries.remove(profileId to networkScopeKey)
    }

    override suspend fun clearProfile(profileId: String) {
        entries.keys.filter { it.first == profileId }.forEach(entries::remove)
    }

    override suspend fun clearAll() {
        entries.clear()
    }
}
