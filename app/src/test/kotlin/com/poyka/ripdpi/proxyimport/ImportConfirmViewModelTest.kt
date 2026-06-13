package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmViewModel
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the minimal import-confirmation destinations. Each confirmation screen shows
 * the parsed profile / subscription and an "Add" action that persists it through
 * [ProxyGroupRepository]. These are the import-confirmation surface, not full editors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportConfirmViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `confirming a profile import persists it into a basic group`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val profile =
                ProxyProfile.Vless(
                    id = "p1",
                    displayName = "Tokyo",
                    groupId = "",
                    server = "example.com",
                    serverPort = 443,
                    uuid = "uuid",
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(
                            FakeRelayProfileStore(),
                            FakeRelayCredentialStore(),
                            FakeAppSettingsRepository(),
                        ),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val groups = repository.list()
            assertEquals(1, groups.size)
            assertEquals(ProxyGroupType.BASIC, groups.single().type)
            assertTrue(viewModel.uiState.value.imported)
        }

    @Test
    fun `confirming a trojan profile import activates the native relay profile`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val trojanCredential = relayImportCredentialFixture("trojan")
            val profile =
                ProxyProfile.Trojan(
                    id = "trojan-node",
                    displayName = "Trojan",
                    groupId = "",
                    server = "trojan.example",
                    serverPort = 443,
                    password = trojanCredential,
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindTrojan, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals(DefaultRelayProfileId, settings.relayProfileId)
            assertEquals(RelayKindTrojan, relayProfile?.kind)
            assertEquals("trojan.example", relayProfile?.server)
            assertEquals("trojan.example", relayProfile?.serverName)
            assertEquals(trojanCredential, relayCredentials?.trojanPassword)
        }

    @Test
    fun `confirming a shadowsocks profile import activates the native relay profile`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val shadowsocksCredential = relayImportCredentialFixture("shadowsocks")
            val profile =
                ProxyProfile.Shadowsocks(
                    id = "ss-node",
                    displayName = "Shadowsocks",
                    groupId = "",
                    server = "ss.example",
                    serverPort = 8388,
                    method = "2022-blake3-aes-256-gcm",
                    password = shadowsocksCredential,
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindShadowsocks, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindShadowsocks, relayProfile?.kind)
            assertEquals("ss.example", relayProfile?.server)
            assertEquals("ss.example", relayProfile?.serverName)
            assertEquals("2022-blake3-aes-256-gcm", relayCredentials?.shadowsocksMethod)
            assertEquals(shadowsocksCredential, relayCredentials?.shadowsocksPassword)
        }

    @Test
    fun `confirming an anytls profile import activates the native relay profile`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val anyTlsCredential = relayImportCredentialFixture("anytls")
            val profile =
                ProxyProfile.AnyTls(
                    id = "anytls-node",
                    displayName = "AnyTLS",
                    groupId = "",
                    server = "anytls.example",
                    serverPort = 443,
                    serverName = "front.example",
                    password = anyTlsCredential,
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindAnyTls, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayKindAnyTls, relayProfile?.kind)
            assertEquals("anytls.example", relayProfile?.server)
            assertEquals("front.example", relayProfile?.serverName)
            assertEquals(anyTlsCredential, relayCredentials?.anyTlsPassword)
        }

    @Test
    fun `confirming a vless-reality profile import activates the native relay profile with reality_tcp transport`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val profile =
                ProxyProfile.VlessReality(
                    id = "reality-node",
                    displayName = "Reality TCP",
                    groupId = "",
                    server = "edge.example.com",
                    serverPort = 443,
                    uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    realityPublicKey = "PUBLICKEY1234567890abcdefghijklmn",
                    realityShortId = "abcd1234",
                    serverName = "target.example.com",
                    flow = "xtls-rprx-vision",
                    fingerprint = "chrome",
                    xhttpPath = null,
                    xhttpHost = null,
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals(DefaultRelayProfileId, settings.relayProfileId)
            assertEquals("edge.example.com", settings.relayServer)
            assertEquals(443, settings.relayServerPort)
            assertEquals("target.example.com", settings.relayServerName)
            assertEquals("PUBLICKEY1234567890abcdefghijklmn", settings.relayRealityPublicKey)
            assertEquals("abcd1234", settings.relayRealityShortId)
            assertEquals(RelayVlessTransportRealityTcp, settings.relayVlessTransport)
            assertEquals(RelayKindVlessReality, relayProfile?.kind)
            assertEquals("edge.example.com", relayProfile?.server)
            assertEquals(443, relayProfile?.serverPort)
            assertEquals("target.example.com", relayProfile?.serverName)
            assertEquals("PUBLICKEY1234567890abcdefghijklmn", relayProfile?.realityPublicKey)
            assertEquals("abcd1234", relayProfile?.realityShortId)
            assertEquals(RelayVlessTransportRealityTcp, relayProfile?.vlessTransport)
            assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", relayCredentials?.vlessUuid)
        }

    @Test
    fun `confirming a vless-reality profile import with xhttp transport sets xhttp transport and path`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val profile =
                ProxyProfile.VlessReality(
                    id = "xhttp-node",
                    displayName = "Reality xHTTP",
                    groupId = "",
                    server = "cdn.example.com",
                    serverPort = 443,
                    uuid = "dddddddd-dddd-dddd-dddd-dddddddddddd",
                    realityPublicKey = "XHTTPKEY1234567890abcdefghijklmn",
                    realityShortId = "cafe0001",
                    serverName = "cdn.example.com",
                    flow = "xtls-rprx-vision",
                    fingerprint = null,
                    xhttpPath = "/tunnel",
                    xhttpHost = "cdn.example.com",
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayVlessTransportXhttp, settings.relayVlessTransport)
            assertEquals("/tunnel", settings.relayXhttpPath)
            assertEquals("cdn.example.com", settings.relayXhttpHost)
            assertEquals("XHTTPKEY1234567890abcdefghijklmn", settings.relayRealityPublicKey)
            assertEquals("cafe0001", settings.relayRealityShortId)
            assertEquals(RelayKindVlessReality, relayProfile?.kind)
            assertEquals(RelayVlessTransportXhttp, relayProfile?.vlessTransport)
            assertEquals("/tunnel", relayProfile?.xhttpPath)
            assertEquals("cdn.example.com", relayProfile?.xhttpHost)
            assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", relayCredentials?.vlessUuid)
        }

    @Test
    fun `confirming an ssh password profile import activates the native relay with udp disabled`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val sshPassword = relayImportCredentialFixture("ssh")
            val profile =
                ProxyProfile.Ssh(
                    id = "ssh-node",
                    displayName = "SSH",
                    groupId = "",
                    server = "ssh.example",
                    serverPort = 22,
                    username = "alice",
                    authType = RelaySshAuthTypePassword,
                    password = sshPassword,
                    hostKeyFingerprint = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg",
                    strictHostKey = true,
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(relayProfileStore, relayCredentialStore, settingsRepository),
                )

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindSsh, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals("ssh.example", settings.relayServer)
            assertEquals(22, settings.relayServerPort)
            // SSH carries only a direct-tcpip TCP channel.
            assertEquals(false, settings.relayUdpEnabled)
            assertEquals(RelaySshAuthTypePassword, settings.relaySshAuthType)
            assertEquals(
                "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg",
                settings.relaySshHostKeyFingerprint,
            )
            assertTrue(settings.relaySshStrictHostKey)
            assertEquals(RelayKindSsh, relayProfile?.kind)
            assertEquals("ssh.example", relayProfile?.server)
            assertEquals(false, relayProfile?.udpEnabled)
            assertEquals(RelaySshAuthTypePassword, relayProfile?.sshAuthType)
            assertTrue(relayProfile?.sshStrictHostKey == true)
            assertEquals("alice", relayCredentials?.sshUsername)
            assertEquals(sshPassword, relayCredentials?.sshPassword)
        }

    @Test
    fun `confirming a subscription import persists a subscription group`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/c", name = "Fleet", bootstrap = false)
            viewModel.confirm()
            advanceUntilIdle()

            val groups = repository.list()
            assertEquals(1, groups.size)
            val group = groups.single()
            assertEquals(ProxyGroupType.SUBSCRIPTION, group.type)
            assertEquals("Fleet", group.name)
            assertEquals("https://sub.example.com/c", group.subscription?.link)
            assertTrue(viewModel.uiState.value.imported)
        }

    @Test
    fun `subscription import without a name falls back to the host as the group name`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/c", name = "", bootstrap = false)
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals("sub.example.com", repository.list().single().name)
        }

    @Test
    fun `bootstrap subscription import is surfaced in ui state`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(
                url = "https://sub.example.com/bootstrap/tok",
                name = "Boot",
                bootstrap = true,
            )

            assertTrue(viewModel.uiState.value.bootstrap)
        }

    @Test
    fun `confirming a bootstrap import persists a bootstrap-kind subscription`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(
                url = "https://sub.example.com/bootstrap/tok",
                name = "Boot",
                bootstrap = true,
            )
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(
                SubscriptionKind.BOOTSTRAP,
                repository
                    .list()
                    .single()
                    .subscription
                    ?.kind,
            )
        }

    @Test
    fun `confirming a non-bootstrap import persists a long-lived subscription`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/sub/x", name = "Fleet", bootstrap = false)
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(
                SubscriptionKind.LONG_LIVED,
                repository
                    .list()
                    .single()
                    .subscription
                    ?.kind,
            )
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

private fun relayImportCredentialFixture(label: String): String =
    listOf("relay", "import", "credential", label).joinToString("-")
