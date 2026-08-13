package com.poyka.ripdpi.proxyimport

import app.cash.turbine.test
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.DefaultServiceStateStore
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
import com.poyka.ripdpi.data.routing.PackageRoutingAction
import com.poyka.ripdpi.data.subscription.BootstrapConsumer
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ImportedRelayProfilePreflight
import com.poyka.ripdpi.services.ImportedRelayProfilePreflightRequest
import com.poyka.ripdpi.services.ImportedRelayProfilePreflightResult
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileCheckState
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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `checking a supported profile projects transient material without importing it`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val profileStore = FakeRelayProfileStore()
            val credentialStore = FakeRelayCredentialStore()
            val requests = mutableListOf<ImportedRelayProfilePreflightRequest>()
            val profile =
                ProxyProfile.VlessReality(
                    id = "candidate",
                    displayName = "Candidate",
                    groupId = "",
                    server = "relay.example",
                    serverPort = 443,
                    uuid = "11111111-2222-3333-4444-555555555555",
                    realityPublicKey = ValidRealityPublicKey,
                    realityShortId = "abcd1234",
                    serverName = "target.example",
                )
            val viewModel =
                ProfileImportConfirmViewModel(
                    repository = repository,
                    relayActivator =
                        RelayProfileActivator(
                            profileStore,
                            credentialStore,
                            FakeAppSettingsRepository(),
                        ),
                    preflight =
                        ImportedRelayProfilePreflight { request ->
                            requests += request
                            ImportedRelayProfilePreflightResult.ReachedTarget
                        },
                    serviceStateStore = DefaultServiceStateStore(),
                )

            viewModel.setProfile(profile)
            viewModel.checkProfile()
            advanceUntilIdle()

            assertEquals(
                listOf(ProfileCheckState.Succeeded, 1, "import-preflight", profile.uuid, 0, null, null),
                listOf(
                    viewModel.uiState.value.checkState,
                    requests.size,
                    requests.single().profile.id,
                    requests.single().credentials.vlessUuid,
                    repository.list().size,
                    profileStore.load("import-preflight"),
                    credentialStore.load("import-preflight"),
                ),
            )
        }

    @Test
    fun `confirming a plain vless import that activates no relay surfaces an error and persists nothing`() =
        runTest {
            // P1-4: a plain vless:// link (no REALITY material) is not a native
            // relay backend, so activate() returns false (a no-op). The import
            // must NOT report success — it is a silent dead-end otherwise — and
            // the phantom group must be rolled back so the user accumulates no
            // non-working entry.
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

            val state = viewModel.uiState.value
            assertFalse(state.importing)
            assertEquals(R.string.import_profile_confirm_error, state.errorRes)
            assertTrue("no phantom group is left for a dead-end import", repository.list().isEmpty())
        }

    @Test
    fun `confirming a tuic raw-config import that activates no relay surfaces an error and persists nothing`() =
        runTest {
            // P1-4: a tuic:// link round-trips as ProxyProfile.RawConfig, which has
            // no native relay backend, so activate() returns false. Same honest
            // dead-end rule as plain vless: error, not imported, no phantom group.
            val repository = FakeProxyGroupRepository()
            val profile =
                ProxyProfile.RawConfig(
                    id = "tuic-node",
                    displayName = "TUIC Tokyo",
                    groupId = "",
                    config = "tuic://uuid:pass@tuic.example:443?sni=tuic.example#TUIC%20Tokyo",
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

            val state = viewModel.uiState.value
            assertEquals(R.string.import_profile_confirm_error, state.errorRes)
            assertTrue("no phantom group is left for a dead-end import", repository.list().isEmpty())
        }

    @Test
    fun `confirming an invalid vless-reality profile surfaces an error instead of importing`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val profile =
                ProxyProfile.VlessReality(
                    id = "bad-node",
                    displayName = "Broken",
                    groupId = "",
                    server = "edge.example.com",
                    serverPort = 443,
                    uuid = "11111111-2222-3333-4444-555555555555",
                    // Not base64 32-byte key material -> validation fails.
                    realityPublicKey = "tooshort",
                    realityShortId = "",
                    serverName = "target.example.com",
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

            val state = viewModel.uiState.value
            assertFalse(state.importing)
            assertEquals(R.string.import_profile_confirm_error, state.errorRes)
            assertTrue("no group is persisted for an invalid import", repository.list().isEmpty())
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
            viewModel.importedEvents.test {
                viewModel.confirm()
                advanceUntilIdle()
                awaitItem()
                viewModel.confirm()
                advanceUntilIdle()
                expectNoEvents()
            }

            val settings = settingsRepository.snapshot()
            val relayProfile = relayProfileStore.load(DefaultRelayProfileId)
            val relayCredentials = relayCredentialStore.load(DefaultRelayProfileId)
            // A genuinely relay-activatable kind reports honest success and keeps
            // the persisted group (the positive counterpart to the P1-4 dead-end).
            assertEquals(1, repository.list().size)
            assertEquals(ProxyGroupType.BASIC, repository.list().single().type)
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
                    realityPublicKey = ValidRealityPublicKey,
                    realityShortId = "abcd1234",
                    serverName = "target.example.com",
                    flow = "xtls-rprx-vision-udp443",
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
            assertEquals(ValidRealityPublicKey, settings.relayRealityPublicKey)
            assertEquals("abcd1234", settings.relayRealityShortId)
            assertEquals(RelayVlessTransportRealityTcp, settings.relayVlessTransport)
            assertFalse(settings.relayUdpEnabled)
            assertEquals(RelayKindVlessReality, relayProfile?.kind)
            assertEquals("edge.example.com", relayProfile?.server)
            assertEquals(443, relayProfile?.serverPort)
            assertEquals("target.example.com", relayProfile?.serverName)
            assertEquals(ValidRealityPublicKey, relayProfile?.realityPublicKey)
            assertEquals("abcd1234", relayProfile?.realityShortId)
            assertEquals(RelayVlessTransportRealityTcp, relayProfile?.vlessTransport)
            assertFalse(relayProfile?.udpEnabled ?: true)
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
                    realityPublicKey = ValidRealityPublicKey,
                    realityShortId = "cafe0001",
                    serverName = "cdn.example.com",
                    flow = "xtls-rprx-vision-udp443",
                    fingerprint = null,
                    xhttpPath = "/tunnel",
                    xhttpHost = "cdn.example.com",
                    xhttpMode = "stream-one",
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
            assertEquals(ValidRealityPublicKey, settings.relayRealityPublicKey)
            assertEquals("cafe0001", settings.relayRealityShortId)
            assertFalse(settings.relayUdpEnabled)
            assertEquals(RelayKindVlessReality, relayProfile?.kind)
            assertEquals("xtls-rprx-vision-udp443", relayProfile?.vlessFlow)
            assertEquals(RelayVlessTransportXhttp, relayProfile?.vlessTransport)
            assertEquals("/tunnel", relayProfile?.xhttpPath)
            assertEquals("cdn.example.com", relayProfile?.xhttpHost)
            assertEquals("stream-one", relayProfile?.xhttpMode)
            assertFalse(relayProfile?.udpEnabled ?: true)
            assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", relayCredentials?.vlessUuid)
        }

    @Test
    fun `confirming a vless-reality profile import with host-only xhttp keeps xhttp transport`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val relayProfileStore = FakeRelayProfileStore()
            val relayCredentialStore = FakeRelayCredentialStore()
            val settingsRepository = FakeAppSettingsRepository()
            val profile =
                ProxyProfile.VlessReality(
                    id = "xhttp-host-node",
                    displayName = "Reality xHTTP host",
                    groupId = "",
                    server = "edge.example.com",
                    serverPort = 443,
                    uuid = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                    realityPublicKey = ValidRealityPublicKey,
                    realityShortId = "cafe0002",
                    serverName = "decoy.example.com",
                    flow = "xtls-rprx-vision",
                    fingerprint = null,
                    xhttpPath = null,
                    xhttpHost = "carrier.example.com",
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
            assertEquals(RelayKindVlessReality, settings.relayKind)
            assertTrue(settings.relayEnabled)
            assertEquals(RelayVlessTransportXhttp, settings.relayVlessTransport)
            assertEquals("", settings.relayXhttpPath)
            assertEquals("carrier.example.com", settings.relayXhttpHost)
            assertEquals(RelayVlessTransportXhttp, relayProfile?.vlessTransport)
            assertEquals("", relayProfile?.xhttpPath)
            assertEquals("carrier.example.com", relayProfile?.xhttpHost)
            assertFalse(relayProfile?.udpEnabled ?: true)
            assertEquals(
                "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                relayCredentialStore.load(DefaultRelayProfileId)?.vlessUuid,
            )
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
}

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionImportConfirmViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `confirming a subscription import persists a subscription group`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/c", name = "Fleet", bootstrap = false)
            viewModel.importedEvents.test {
                viewModel.confirm()
                advanceUntilIdle()
                awaitItem()
                expectNoEvents()
            }

            val groups = repository.list()
            assertEquals(1, groups.size)
            val group = groups.single()
            assertEquals(ProxyGroupType.SUBSCRIPTION, group.type)
            assertEquals("Fleet", group.name)
            assertEquals("https://sub.example.com/c", group.subscription?.link)
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
    fun `confirming a bootstrap import consumes and persists members before success`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("trojan://secret@example.com:443#bootstrap")
                        .build(),
                )
                server.start()
                val repository = FakeProxyGroupRepository()
                val viewModel =
                    SubscriptionImportConfirmViewModel(
                        repository = repository,
                        bootstrapConsumer = BootstrapConsumer(clockMillis = { 42L }),
                        groupIdFactory = { "bootstrap-group" },
                    )

                viewModel.setRequest(
                    url = server.url("/bootstrap/tok").toString(),
                    name = "Boot",
                    bootstrap = true,
                )
                viewModel.importedEvents.test {
                    viewModel.confirm()
                    advanceUntilIdle()
                    awaitItem()
                }

                val group = repository.list().single()
                assertEquals(SubscriptionKind.BOOTSTRAP, group.subscription?.kind)
                assertEquals(42L, group.subscription?.consumedAt)
                assertEquals(1, group.members.size)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `bootstrap import persists package routes on the subscription group`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "outbounds":[{"type":"trojan","tag":"n","server":"n.example",
                                "server_port":443,"password":"p"}],
                              "route":{"rules":[{"package_name":["com.imported.app"],"outbound":"select"}]}
                            }
                            """.trimIndent(),
                        ).build(),
                )
                server.start()
                val repository = FakeProxyGroupRepository()
                val viewModel =
                    SubscriptionImportConfirmViewModel(
                        repository = repository,
                        bootstrapConsumer = BootstrapConsumer(clockMillis = { 42L }),
                        groupIdFactory = { "bootstrap-routes" },
                    )
                viewModel.setRequest(server.url("/bootstrap/routes").toString(), "Boot", bootstrap = true)

                viewModel.importedEvents.test {
                    viewModel.confirm()
                    advanceUntilIdle()
                    awaitItem()
                }

                val rule =
                    repository
                        .list()
                        .single()
                        .packageRoutingRules
                        .single()
                assertEquals("com.imported.app", rule.packageName)
                assertEquals(PackageRoutingAction.VIA_TUN, rule.action)
            }
        }

    @Test
    fun `AWG-only bootstrap persists the profile before reporting import success`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(
                            """
                            {
                              "outbounds": [],
                              "ripdpi": {
                                "schema_version": 1,
                                "amneziawg": [{
                                  "tag": "bootstrap-awg",
                                  "private_key": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                                  "address": ["10.8.0.2/32"],
                                  "peer": {
                                    "public_key": "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                                    "endpoint": "192.0.2.10:51820",
                                    "allowed_ips": ["0.0.0.0/0"]
                                  }
                                }],
                                "hysteria_extras": {}
                              }
                            }
                            """.trimIndent(),
                        ).build(),
                )
                server.start()
                val repository = FakeProxyGroupRepository()
                val savedAwgNames = mutableListOf<String>()
                val viewModel =
                    SubscriptionImportConfirmViewModel(
                        repository = repository,
                        bootstrapConsumer = BootstrapConsumer(clockMillis = { 42L }),
                        groupIdFactory = { "bootstrap-awg" },
                        saveAwgProfiles = { profiles ->
                            savedAwgNames += profiles.map { it.displayName }
                        },
                    )
                viewModel.setRequest(server.url("/bootstrap/awg").toString(), "Boot", bootstrap = true)

                viewModel.importedEvents.test {
                    viewModel.confirm()
                    advanceUntilIdle()
                    awaitItem()
                }

                assertEquals(listOf("bootstrap-awg"), savedAwgNames)
                assertTrue(
                    repository
                        .list()
                        .single()
                        .members
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `failed bootstrap consume does not persist an empty group`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(410).build())
                server.start()
                val repository = FakeProxyGroupRepository()
                val viewModel =
                    SubscriptionImportConfirmViewModel(
                        repository = repository,
                        bootstrapConsumer = BootstrapConsumer(),
                    )

                viewModel.setRequest(server.url("/bootstrap/spent").toString(), "Boot", bootstrap = true)
                viewModel.confirm()
                viewModel.uiState.first { it.importFailed }

                assertTrue(repository.list().isEmpty())
                assertTrue(viewModel.uiState.value.importFailed)
            }
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

private const val ValidRealityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s="
