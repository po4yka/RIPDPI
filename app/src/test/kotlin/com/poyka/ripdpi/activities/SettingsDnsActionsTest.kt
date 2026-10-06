package com.poyka.ripdpi.activities

import androidx.datastore.core.DataStoreFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultAppSettingsRepository
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

internal const val ValidOdohConfigsHex =
    "002c000100280020000100010020c6a793bedbd601c25970b1cc46bea80fdb1a8ec51540d79e4f9f17b8baa9da33"

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDnsActionsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `switch from custom encrypted to plain dns is durable and clears stale resolver fields`() =
        runTest {
            val controller = FakeServiceController()
            var saved = AppSettings.getDefaultInstance()
            withPersistentRepository { repository ->
                val actions =
                    createActions(repository = repository, serviceController = controller, scope = backgroundScope)
                actions.setCustomDohResolver(" https://resolver.example:8443/dns-query ", listOf("1.1.1.1", "1.1.1.1"))
                backgroundScope.coroutineContext.job.children
                    .toList()
                    .joinAll()
                val encrypted = repository.snapshot()
                assertEquals("resolver.example", encrypted.encryptedDnsHost)
                assertEquals(8443, encrypted.encryptedDnsPort)
                assertEquals(listOf("1.1.1.1"), encrypted.encryptedDnsBootstrapIpsList)
                actions.setPlainDnsServer("9.9.9.9")
                backgroundScope.coroutineContext.job.children
                    .toList()
                    .joinAll()
                saved = repository.snapshot()
                assertEquals(DnsModePlainUdp, saved.dnsMode)
                assertEquals("9.9.9.9", saved.dnsIp)
                assertEquals("", saved.encryptedDnsProtocol)
                assertEquals("", saved.encryptedDnsHost)
                assertEquals(0, saved.encryptedDnsPort)
                assertEquals("", saved.encryptedDnsDohUrl)
                assertEquals("", saved.encryptedDnsTlsServerName)
                assertTrue(saved.encryptedDnsBootstrapIpsList.isEmpty())
            }

            withPersistentRepository { assertEquals(saved, it.snapshot()) }
            assertEquals(0, controller.stopCount)
            assertTrue(controller.startedModes.isEmpty())
        }

    @Test
    fun `doq rejected by persisted vpn mode leaves settings unchanged across reopen`() =
        runTest {
            var before = AppSettings.getDefaultInstance()
            val controller = FakeServiceController()
            withPersistentRepository { repository ->
                repository.update { setRipdpiMode(Mode.VPN.preferenceValue) }
                before = repository.snapshot()
                createActions(repository = repository, serviceController = controller, scope = backgroundScope)
                    .setCustomDotResolver(
                        protocol = EncryptedDnsProtocolDoq,
                        selectedMode = Mode.Proxy,
                        host = "resolver.example",
                        port = 853,
                        tlsServerName = "resolver.example",
                        bootstrapIps = listOf("1.1.1.1"),
                    )
                backgroundScope.coroutineContext.job.children
                    .toList()
                    .joinAll()
                assertEquals(before, repository.snapshot())
            }

            withPersistentRepository { assertEquals(before, it.snapshot()) }
            assertEquals(0, controller.stopCount)
            assertTrue(controller.startedModes.isEmpty())
        }

    @Test
    fun `dns save leaves halted runtime for next start`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN)
            val repository = FakeAppSettingsRepository()
            val actions =
                createActions(
                    repository = repository,
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                )

            actions.setPlainDnsServer("9.9.9.9")
            advanceUntilIdle()

            val settings = repository.snapshot()
            assertEquals(DnsModePlainUdp, settings.dnsMode)
            assertEquals("9.9.9.9", settings.dnsIp)
            assertEquals(0, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
        }

    @Test
    fun `dns save restarts running runtime with active mode`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val actions =
                createActions(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                )

            actions.setPlainDnsServer("9.9.9.9")
            runCurrent()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())

            serviceStateStore.setStatus(AppStatus.Halted, Mode.Proxy)
            advanceUntilIdle()

            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
        }

    @Test
    fun `built in dns provider save applies after active vpn service halts`() =
        runTest {
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val actions =
                createActions(
                    serviceStateStore = serviceStateStore,
                    serviceController = serviceController,
                )

            actions.selectBuiltInDnsProvider(DnsProviderCloudflare)
            runCurrent()

            assertEquals(1, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            advanceUntilIdle()

            assertEquals(listOf(Mode.VPN), serviceController.startedModes)
        }

    @Test
    fun `doq cannot replace a running resolver over unsupported vpn transport`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val serviceController = FakeServiceController()
            val actions =
                createActions(
                    repository = repository,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.VPN),
                    serviceController = serviceController,
                )
            val before = repository.snapshot()

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                Mode.Proxy,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            advanceUntilIdle()

            assertEquals(before, repository.snapshot())
            assertEquals(0, serviceController.stopCount)
            assertTrue(serviceController.startedModes.isEmpty())
        }

    @Test
    fun `doq saves and restarts an active proxy resolver`() =
        runTest {
            val repository =
                FakeAppSettingsRepository(
                    com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.Proxy.preferenceValue)
                        .build(),
                )
            val serviceController = FakeServiceController()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val actions = createActions(repository, serviceStateStore, serviceController)

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                Mode.Proxy,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            runCurrent()

            val settings = repository.snapshot()
            assertEquals(EncryptedDnsProtocolDoq, settings.encryptedDnsProtocol)
            assertEquals("quic.example", settings.encryptedDnsTlsServerName)
            assertEquals(1, serviceController.stopCount)
            serviceStateStore.setStatus(AppStatus.Halted, Mode.Proxy)
            advanceUntilIdle()
            assertEquals(listOf(Mode.Proxy), serviceController.startedModes)
        }

    @Test
    fun `doq transaction refuses vpn that starts after save was requested`() =
        runTest {
            val backing =
                FakeAppSettingsRepository(
                    com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.Proxy.preferenceValue)
                        .build(),
                )
            val serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy)
            val repository =
                object : AppSettingsRepository by backing {
                    override suspend fun update(transform: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Unit) {
                        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
                        backing.update(transform)
                    }
                }
            val controller = FakeServiceController()
            val actions = createActions(repository, serviceStateStore, controller)
            val before = backing.snapshot()

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                Mode.Proxy,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            advanceUntilIdle()

            assertEquals(before, backing.snapshot())
            assertEquals(0, controller.stopCount)
        }

    @Test
    fun `doq save lease rejects vpn dispatch until data store update completes`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val receipt = authority.reserveStart(Mode.VPN)
            val arbiter = ServiceIntentArbiter(authority)
            val backing =
                FakeAppSettingsRepository(
                    com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.Proxy.preferenceValue)
                        .build(),
                )
            var startResult: ServiceStartResult? = null
            val repository =
                object : AppSettingsRepository by backing {
                    override suspend fun update(transform: com.poyka.ripdpi.proto.AppSettings.Builder.() -> Unit) {
                        startResult = arbiter.dispatchVpnStart { ServiceStartResult.Accepted(receipt) }
                        backing.update(transform)
                    }
                }
            val actions =
                createActions(
                    repository = repository,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy),
                    serviceIntentArbiter = arbiter,
                )

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                Mode.Proxy,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            advanceUntilIdle()

            assertEquals(
                ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.DnsSettingsUpdatePending),
                startResult,
            )
            assertEquals(EncryptedDnsProtocolDoq, backing.snapshot().encryptedDnsProtocol)
            assertEquals(
                ServiceStartResult.Accepted(receipt),
                arbiter.dispatchVpnStart { ServiceStartResult.Accepted(receipt) },
            )
        }

    @Test
    fun `doq save refuses a pending vpn start before status leaves halted`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val receipt = authority.reserveStart(Mode.VPN)
            val arbiter = ServiceIntentArbiter(authority)
            val repository =
                FakeAppSettingsRepository(
                    com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setRipdpiMode(Mode.Proxy.preferenceValue)
                        .build(),
                )
            val before = repository.snapshot()
            val actions =
                createActions(
                    repository = repository,
                    serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.Proxy),
                    serviceIntentArbiter = arbiter,
                )
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(receipt) }

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                Mode.Proxy,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            advanceUntilIdle()

            assertEquals(before, repository.snapshot())
            arbiter.completeVpnStart(arbiter.captureVpnStartGeneration())
        }

    @Test
    fun `doq cannot save for selected vpn`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val serviceStateStore = FakeServiceStateStore(AppStatus.Halted to Mode.VPN)
            val actions = createActions(repository, serviceStateStore)
            val before = repository.snapshot()

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                Mode.VPN,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            advanceUntilIdle()

            assertEquals(before, repository.snapshot())
        }

    @Test
    fun `custom odoh save preserves protocol and all target fields`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val actions = createActions(repository = repository)
            val fields =
                OdohResolverFields(
                    proxyUrl = "https://proxy.example/dns-query",
                    proxyOperatorId = "proxy-operator",
                    targetHost = "target.example",
                    targetPath = "/dns-query",
                    targetOperatorId = "target-operator",
                    configSource = EncryptedDnsOdohConfigSourceCustomBytes,
                    configsHex = ValidOdohConfigsHex,
                    configsRetrievedAtSecs = System.currentTimeMillis() / 1_000,
                    configsTtlSecs = 86_400,
                )

            actions.setCustomOdohResolver(fields, listOf("1.1.1.1"))
            advanceUntilIdle()

            val settings = repository.snapshot()
            assertEquals(EncryptedDnsProtocolOdoh, settings.encryptedDnsProtocol)
            assertEquals(fields.proxyUrl, settings.encryptedDnsOdohProxyUrl)
            assertEquals(fields.proxyOperatorId, settings.encryptedDnsOdohProxyOperatorId)
            assertEquals(fields.targetHost, settings.encryptedDnsOdohTargetHost)
            assertEquals(fields.targetPath, settings.encryptedDnsOdohTargetPath)
            assertEquals(fields.targetOperatorId, settings.encryptedDnsOdohTargetOperatorId)
            assertEquals(fields.configSource, settings.encryptedDnsOdohConfigSource)
            assertEquals(fields.configsHex, settings.encryptedDnsOdohConfigsHex)
            assertEquals(fields.configsRetrievedAtSecs, settings.encryptedDnsOdohConfigsRetrievedAtSecs)
            assertEquals(fields.configsTtlSecs, settings.encryptedDnsOdohConfigsTtlSecs)
        }

    @Test
    fun `incomplete odoh input cannot overwrite resolver`() {
        val actions = createActions()
        assertThrows(IllegalArgumentException::class.java) {
            actions.setCustomOdohResolver(OdohResolverFields(proxyUrl = "https://proxy.example"), listOf("1.1.1.1"))
        }
    }

    @Test
    fun `odoh rejects colluding operators before persistence`() {
        val actions = createActions()
        val fields =
            OdohResolverFields(
                proxyUrl = "https://proxy.example/dns-query",
                proxyOperatorId = "same-operator",
                targetHost = "target.example",
                targetPath = "/dns-query",
                targetOperatorId = "SAME-OPERATOR",
                configSource = EncryptedDnsOdohConfigSourceCustomBytes,
                configsHex = ValidOdohConfigsHex,
                configsRetrievedAtSecs = System.currentTimeMillis() / 1_000,
                configsTtlSecs = 86_400,
            )
        assertThrows(IllegalArgumentException::class.java) {
            actions.setCustomOdohResolver(fields, listOf("1.1.1.1"))
        }
    }

    private fun createActions(
        repository: AppSettingsRepository = FakeAppSettingsRepository(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        serviceController: FakeServiceController = FakeServiceController(),
        serviceIntentArbiter: ServiceIntentArbiter =
            ServiceIntentArbiter(
                com.poyka.ripdpi.data
                    .testPauseAuthority(),
            ),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    ): SettingsDnsActions =
        SettingsDnsActions(
            mutations =
                SettingsMutationRunner(
                    scope = scope,
                    appSettingsRepository = repository,
                    effects =
                        MutableSharedFlow(
                            replay = 16,
                            onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        ),
                ),
            serviceStateStore = serviceStateStore,
            reconnectCoordinator = FakeRunningServiceReconnect(serviceController, serviceStateStore),
            onReconnectFailure = {},
            serviceIntentArbiter = serviceIntentArbiter,
        )

    private suspend fun withPersistentRepository(block: suspend (AppSettingsRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store =
            DataStoreFactory.create(
                serializer = AppSettingsSerializer,
                scope = scope,
                produceFile = { File(temporaryFolder.root, "dns-settings.pb") },
            )
        try {
            block(DefaultAppSettingsRepository(store))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
