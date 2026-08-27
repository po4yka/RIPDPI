package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelaySecurityLayerTls
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.subscription.XrayConfigImportParser
import com.poyka.ripdpi.data.subscription.XrayConfigImportResult
import com.poyka.ripdpi.data.xray.DefaultXrayProfileId
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.ui.screens.proxyimport.NativeRelayProfileActivator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore as DurableXrayProviderSelectionStore

/**
 * Integration coverage for the full Xray import path: parse an xray-core config
 * with [XrayConfigImportParser], then activate the translated [ProxyProfile] via
 * [NativeRelayProfileActivator] and assert it lands as the exact native relay
 * configuration the engine consumes (relay enabled, kind, endpoint, secrets).
 *
 * The resulting relay config is the same shape the native relay backend builder
 * is exercised against in `ripdpi-relay-core` (the `vless_reality` / `trojan`
 * `build_backend` tests) — so a translated profile yields a runnable backend
 * rather than a config that fails at connect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XrayImportNativeActivationTest {
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"
    private val pbk = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="

    private fun firstProfile(input: String): ProxyProfile {
        val result = XrayConfigImportParser.parse(input, groupId = "xray-import")
        assertTrue(result is XrayConfigImportResult.Translated)
        return (result as XrayConfigImportResult.Translated).profiles.first()
    }

    @Test
    fun `translated vless reality config activates a runnable native relay`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            val config =
                """
                { "outbounds": [ {
                  "tag": "tokyo", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                    "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality",
                    "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com", "shortId": "ab12" } }
                } ] }
                """.trimIndent()

            activator.activate(firstProfile(config))

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val credentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertEquals("edge.example.com", settings.relayServer)
            assertEquals("www.cloudflare.com", settings.relayServerName)
            assertEquals(pbk, settings.relayRealityPublicKey)
            assertEquals(RelayKindVlessReality, relayProfile?.kind)
            assertEquals(RelayVlessTransportRealityTcp, relayProfile?.vlessTransport)
            assertEquals(pbk, relayProfile?.realityPublicKey)
            assertEquals(uuid, credentials?.vlessUuid)
            assertEquals(1, repository.list().size)
            assertEquals(ProxyGroupType.BASIC, repository.list().single().type)
        }

    @Test
    fun `malformed translated vless reality config fails before native relay persistence`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )
            val malformedPublicKey = "not-a-valid-reality-public-key"
            val config =
                """
                { "outbounds": [ {
                  "tag": "bad-reality", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                    "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                  "streamSettings": { "network": "tcp", "security": "reality",
                    "realitySettings": { "publicKey": "$malformedPublicKey", "serverName": "www.cloudflare.com", "shortId": "ab12" } }
                } ] }
                """.trimIndent()

            val error = runCatching { activator.activate(firstProfile(config)) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(malformedPublicKey))
            assertEquals(0, repository.list().size)
            assertNull(relayProfileStore.load(DefaultRelayProfileId))
            assertNull(relayCredentialStore.load(DefaultRelayProfileId))
            assertFalse(settingsRepository.snapshot().relayEnabled)
        }

    @Test
    fun `translated vless reality xhttp with unsupported mode fails before native relay persistence`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )
            val unsupportedMode = "packet-up"
            val config =
                """
                { "outbounds": [ {
                  "tag": "bad-xhttp-mode", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                    "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "reality",
                    "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com", "shortId": "ab12" },
                    "xhttpSettings": { "path": "/tunnel", "mode": "$unsupportedMode" } }
                } ] }
                """.trimIndent()

            val error = runCatching { activator.activate(firstProfile(config)) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(unsupportedMode))
            assertEquals(0, repository.list().size)
            assertNull(relayProfileStore.load(DefaultRelayProfileId))
            assertNull(relayCredentialStore.load(DefaultRelayProfileId))
            assertFalse(settingsRepository.snapshot().relayEnabled)
        }

    @Test
    fun `translated vless reality host-only xhttp config keeps xhttp transport`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            val config =
                """
                { "outbounds": [ {
                  "tag": "host-only-xhttp", "protocol": "vless",
                  "settings": { "vnext": [ { "address": "edge.example.com", "port": 443,
                    "users": [ { "id": "$uuid", "flow": "xtls-rprx-vision" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "reality",
                    "realitySettings": { "publicKey": "$pbk", "serverName": "www.cloudflare.com", "shortId": "ab12" },
                    "xhttpSettings": { "host": "carrier.example.com" } }
                } ] }
                """.trimIndent()

            activator.activate(firstProfile(config))

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertEquals(RelayVlessTransportXhttp, settings.relayVlessTransport)
            assertEquals("", settings.relayXhttpPath)
            assertEquals("carrier.example.com", settings.relayXhttpHost)
            assertEquals(RelayVlessTransportXhttp, relayProfile?.vlessTransport)
            assertEquals("", relayProfile?.xhttpPath)
            assertEquals("carrier.example.com", relayProfile?.xhttpHost)
            assertEquals(uuid, relayCredentialStore.load(DefaultRelayProfileId)?.vlessUuid)
        }

    @Test
    fun `translated empty xhttp and flow remain authoritative at activation`() =
        runTest {
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator = RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository)
            val profile =
                ProxyProfile.VlessReality(
                    id = "id",
                    displayName = "empty-xhttp",
                    groupId = "group",
                    server = "edge.example.com",
                    serverPort = 443,
                    uuid = uuid,
                    realityPublicKey = pbk,
                    realityShortId = "ab12",
                    serverName = "edge.example.com",
                    flow = "",
                    fingerprint = "firefox",
                    xhttpPath = "",
                    xhttpHost = "",
                )

            assertTrue(activator.activate(profile))

            val stored = relayProfileStore.load(DefaultRelayProfileId)!!
            assertEquals(RelayVlessTransportXhttp, stored.vlessTransport)
            assertEquals("", stored.vlessFlow)
            assertEquals("firefox", stored.vlessFingerprint)
            assertEquals("", stored.xhttpPath)
            assertEquals("", stored.xhttpHost)
        }

    @Test
    fun `hysteria identity and authentication survive activation`() =
        runTest {
            val passwordFixture = "test-value"
            val obfsPasswordFixture = "obfs-test-value"
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator = RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository)
            val profile =
                ProxyProfile.Hysteria2(
                    id = "id",
                    displayName = "hy2",
                    groupId = "group",
                    server = "203.0.113.9",
                    serverPort = 443,
                    password = passwordFixture,
                    serverName = "hy.example",
                    obfsPassword = obfsPasswordFixture,
                    insecure = true,
                )

            assertTrue(activator.activate(profile))

            val stored = relayProfileStore.load(DefaultRelayProfileId)!!
            val credentials = relayCredentialStore.load(DefaultRelayProfileId)!!
            assertEquals(RelayKindHysteria2, stored.kind)
            assertEquals("hy.example", stored.serverName)
            assertEquals(passwordFixture, credentials.hysteriaPassword)
            assertEquals(obfsPasswordFixture, credentials.hysteriaSalamanderKey)
            assertEquals(true, credentials.hysteriaInsecure)
        }

    @Test
    fun `plain vless xhttp identity survives activation`() =
        runTest {
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val activator = RelayProfileActivator(relayProfileStore, relayCredentialStore, FakeAppSettingsRepository())
            val profile =
                ProxyProfile.Vless(
                    id = "id",
                    displayName = "plain-xhttp",
                    groupId = "group",
                    server = "203.0.113.4",
                    serverPort = 443,
                    uuid = uuid,
                    serverName = "cdn.example",
                    flow = "",
                    fingerprint = "firefox",
                    xhttpPath = "",
                    xhttpHost = "",
                )

            assertTrue(activator.activate(profile))

            val stored = relayProfileStore.load(DefaultRelayProfileId)!!
            assertEquals(com.poyka.ripdpi.data.RelayKindVless, stored.kind)
            assertEquals(com.poyka.ripdpi.data.RelaySecurityLayerTls, stored.securityLayer)
            assertEquals(RelayVlessTransportXhttp, stored.vlessTransport)
            assertEquals("cdn.example", stored.serverName)
            assertEquals("", stored.vlessFlow)
            assertEquals("firefox", stored.vlessFingerprint)
            assertEquals(uuid, relayCredentialStore.load(DefaultRelayProfileId)?.vlessUuid)
        }

    @Test
    fun `translated trojan config activates a runnable native relay`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            val config =
                """
                { "outbounds": [ { "protocol": "trojan", "settings": { "servers": [
                  { "address": "tj.example", "port": 443, "password": "tj-secret" } ] } } ] }
                """.trimIndent()

            activator.activate(firstProfile(config))

            val settings = settingsRepository.snapshot()
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindTrojan, settings.relayKind)
            assertEquals("tj.example", settings.relayServer)
            assertEquals("tj-secret", relayCredentialStore.load(DefaultRelayProfileId)?.trojanPassword)
        }

    // -----------------------------------------------------------------------
    // DefaultXrayProfilePersistence — import -> durable-store WRITE wiring.
    // -----------------------------------------------------------------------

    private fun xrayProfile(): XrayProfile =
        XrayProfile(
            name = "tokyo",
            outbound =
                XrayProfile.Outbound(
                    serverAddress = "edge.example.com",
                    serverPort = 443,
                    uuid = uuid,
                    security = XrayProfile.Security.REALITY,
                    network = XrayProfile.Network.TCP,
                    reality =
                        XrayProfile.Reality(
                            publicKey = pbk,
                            serverName = "www.cloudflare.com",
                            shortId = "ab12",
                        ),
                ),
        )

    private fun persistence(
        settingsRepository: FakeAppSettingsRepository,
        durableProfileStore: FakeDurableXrayProfileStore,
        durableSelectionStore: FakeDurableXrayProviderSelectionStore,
        appSelectionStore: XrayProviderSelectionStore,
        activator: NativeRelayProfileActivator,
    ): DefaultXrayProfilePersistence =
        DefaultXrayProfilePersistence(
            appSettingsRepository = settingsRepository,
            selectionStore = appSelectionStore,
            stringResolver = FakeStringResolver(),
            relayActivator = activator,
            durableProfileStore = durableProfileStore,
            durableSelectionStore = durableSelectionStore,
        )

    @Test
    fun `xray option with typed profile persists durably and flips selection without native relay`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val durableProfileStore = FakeDurableXrayProfileStore()
            val durableSelectionStore = FakeDurableXrayProviderSelectionStore()
            val appSelectionStore = XrayProviderSelectionStore()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )
            val persistence =
                persistence(
                    settingsRepository,
                    durableProfileStore,
                    durableSelectionStore,
                    appSelectionStore,
                    activator,
                )

            val profile = xrayProfile()
            persistence.persist(
                XrayServiceModeOption.XrayVpn,
                listOf(
                    ProxyProfile.VlessReality(
                        id = "p1",
                        displayName = "tokyo",
                        groupId = "g",
                        server = "edge.example.com",
                        serverPort = 443,
                        uuid = uuid,
                        realityPublicKey = pbk,
                        realityShortId = "ab12",
                        serverName = "www.cloudflare.com",
                    ),
                ),
                profile,
            )

            // Durable profile saved exactly once under the default id.
            assertEquals(1, durableProfileStore.saves.size)
            assertEquals(DefaultXrayProfileId, durableProfileStore.saves.single().first)
            assertEquals(profile, durableProfileStore.saves.single().second)
            // Durable selection flipped to Xray pointing at the default profile.
            assertEquals(VpnProviderKind.Xray, durableSelectionStore.current().kind)
            assertEquals(DefaultXrayProfileId, durableSelectionStore.current().activeProfileId)
            // libXray owns the connection: NO native relay activated.
            assertFalse(settingsRepository.snapshot().relayEnabled)
            assertEquals(0, repository.list().size)
            // Mode is VPN; in-memory selection carries the real profile.
            assertEquals(Mode.VPN.preferenceValue, settingsRepository.snapshot().ripdpiMode)
            assertEquals(profile, appSelectionStore.selection.first().acceptedProfile)
        }

    @Test
    fun `xray option with null profile fails closed without persisting or selecting`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val durableProfileStore = FakeDurableXrayProfileStore()
            val durableSelectionStore = FakeDurableXrayProviderSelectionStore()
            val appSelectionStore = XrayProviderSelectionStore()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )
            val persistence =
                persistence(
                    settingsRepository,
                    durableProfileStore,
                    durableSelectionStore,
                    appSelectionStore,
                    activator,
                )

            val error =
                runCatching {
                    persistence.persist(XrayServiceModeOption.XrayVpn, emptyList(), acceptedProfile = null)
                }.exceptionOrNull()
            // Fail-closed: a typed-less Xray selection throws (the VM surfaces this as
            // persistFailedMessage), it does not silently persist or fall back to native.
            assertTrue(error is IllegalArgumentException)
            // The thrown message must not leak any profile/secret material.
            assertFalse(error?.message.orEmpty().contains(uuid))
            assertFalse(error?.message.orEmpty().contains(pbk))
            // No durable save, no xray selection, no native relay, no group.
            assertEquals(0, durableProfileStore.saves.size)
            assertEquals(VpnProviderKind.Native, durableSelectionStore.current().kind)
            assertFalse(settingsRepository.snapshot().relayEnabled)
            assertEquals(0, repository.list().size)
        }

    @Test
    fun `native option clears xray selection and activates relay without durable save`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            // Seed a stale Xray profile + selection to prove the native path clears
            // BOTH (so the deselected secret does not linger encrypted-at-rest).
            val durableProfileStore =
                FakeDurableXrayProfileStore(seed = DefaultXrayProfileId to xrayProfile())
            val durableSelectionStore =
                FakeDurableXrayProviderSelectionStore(
                    XrayProviderSelectionRecord(
                        providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                        activeProfileId = DefaultXrayProfileId,
                    ),
                )
            val appSelectionStore = XrayProviderSelectionStore()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )
            val persistence =
                persistence(
                    settingsRepository,
                    durableProfileStore,
                    durableSelectionStore,
                    appSelectionStore,
                    activator,
                )

            persistence.persist(
                XrayServiceModeOption.NativeProxy,
                listOf(firstProfile("trojan://pw@tj.example:443#n")),
                acceptedProfile = null,
            )

            // Stale Xray selection cleared to native; no durable profile save.
            assertEquals(VpnProviderKind.Native, durableSelectionStore.current().kind)
            assertEquals(0, durableProfileStore.saves.size)
            assertNull(durableProfileStore.saves.firstOrNull())
            // The orphaned Xray secret is cleared from the durable store at-rest.
            assertTrue(durableProfileStore.clears.contains(DefaultXrayProfileId))
            assertNull(durableProfileStore.load(DefaultXrayProfileId))
            // Native relay activated (existing behaviour) and mode is proxy.
            assertTrue(settingsRepository.snapshot().relayEnabled)
            assertEquals(Mode.Proxy.preferenceValue, settingsRepository.snapshot().ripdpiMode)
            assertEquals(1, repository.list().size)
        }

    @Test
    fun `native option activates translated plain tls vless xhttp`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val durableProfileStore = FakeDurableXrayProfileStore()
            val durableSelectionStore = FakeDurableXrayProviderSelectionStore()
            val activator =
                NativeRelayProfileActivator(
                    repository,
                    RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )
            val persistence =
                persistence(
                    settingsRepository,
                    durableProfileStore,
                    durableSelectionStore,
                    XrayProviderSelectionStore(),
                    activator,
                )
            val config =
                """
                { "outbounds": [ { "protocol": "vless", "tag": "plain-xhttp",
                  "settings": { "vnext": [ { "address": "203.0.113.4", "port": 443,
                    "users": [ { "id": "$uuid", "flow": "" } ] } ] },
                  "streamSettings": { "network": "xhttp", "security": "tls",
                    "tlsSettings": { "serverName": "cdn.example", "fingerprint": "firefox" },
                    "xhttpSettings": { "path": "", "host": "", "mode": "auto" } } } ] }
                """.trimIndent()

            persistence.persist(
                XrayServiceModeOption.NativeProxy,
                listOf(firstProfile(config)),
                acceptedProfile = null,
            )

            val stored = relayProfileStore.load(DefaultRelayProfileId)
            assertTrue(settingsRepository.snapshot().relayEnabled)
            assertEquals(RelayKindVless, stored?.kind)
            assertEquals(RelaySecurityLayerTls, stored?.securityLayer)
            assertEquals(RelayVlessTransportXhttp, stored?.vlessTransport)
            assertEquals(uuid, relayCredentialStore.load(DefaultRelayProfileId)?.vlessUuid)
            assertEquals(1, repository.list().size)
        }
}

private class FakeProxyGroupRepository : ProxyGroupRepository {
    private val state = MutableStateFlow<List<ProxyGroup>>(emptyList())

    override suspend fun add(group: ProxyGroup) {
        state.value = state.value.filterNot { it.id == group.id } + group
    }

    override suspend fun update(group: ProxyGroup) {
        state.value = state.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun list(): List<ProxyGroup> = state.value

    override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
}

private class FakeRelayProfileStore : RelayProfileStore {
    private val profiles = mutableMapOf<String, RelayProfileRecord>()

    override suspend fun load(profileId: String): RelayProfileRecord? = profiles[profileId]

    override suspend fun list(): List<RelayProfileRecord> = profiles.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        profiles[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        profiles.remove(profileId)
    }
}

private class FakeRelayCredentialStore : RelayCredentialStore {
    private val credentials = mutableMapOf<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String): RelayCredentialRecord? = credentials[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        this.credentials[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings = settings.first()

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

/**
 * In-memory [DurableXrayProfileStore] for host-JVM tests — never classloads the
 * real Keystore-backed implementation. Records every save so the wiring test can
 * assert the profile id and contents written.
 */
private class FakeDurableXrayProfileStore(
    seed: Pair<String, XrayProfile>? = null,
) : DurableXrayProfileStore {
    val saves = mutableListOf<Pair<String, XrayProfile>>()
    val clears = mutableListOf<String>()
    private val profiles = mutableMapOf<String, XrayProfile>()

    init {
        seed?.let { (id, profile) -> profiles[id] = profile }
    }

    override suspend fun load(profileId: String): XrayProfile? = profiles[profileId]

    override suspend fun save(
        profileId: String,
        profile: XrayProfile,
    ) {
        saves += profileId to profile
        profiles[profileId] = profile
    }

    override suspend fun clear(profileId: String) {
        clears += profileId
        profiles.remove(profileId)
    }

    override suspend fun listProfileIds(): List<String> = profiles.keys.toList()
}

/** In-memory durable selection store; SharedPreferences-free for host-JVM tests. */
private class FakeDurableXrayProviderSelectionStore(
    initial: XrayProviderSelectionRecord = XrayProviderSelectionRecord(),
) : DurableXrayProviderSelectionStore {
    private var record = initial

    override fun current(): XrayProviderSelectionRecord = record

    override fun update(record: XrayProviderSelectionRecord) {
        this.record = record
    }
}

/** Returns the resource id as a stable token; no Android resources on host JVM. */
private class FakeStringResolver : StringResolver {
    override fun getString(
        resId: Int,
        vararg formatArgs: Any,
    ): String = "string:$resId"
}
