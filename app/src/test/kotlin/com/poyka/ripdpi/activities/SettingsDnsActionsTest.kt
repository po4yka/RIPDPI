package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

internal const val ValidOdohConfigsHex =
    "002c000100280020000100010020c6a793bedbd601c25970b1cc46bea80fdb1a8ec51540d79e4f9f17b8baa9da33"

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDnsActionsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
            val arbiter = ServiceIntentArbiter()
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
                        startResult = arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
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
                ServiceStartResult.Accepted(Mode.VPN),
                arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) },
            )
        }

    @Test
    fun `doq save refuses a pending vpn start before status leaves halted`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
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
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }

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
        serviceIntentArbiter: ServiceIntentArbiter = ServiceIntentArbiter(),
    ): SettingsDnsActions =
        SettingsDnsActions(
            mutations =
                SettingsMutationRunner(
                    scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
                    appSettingsRepository = repository,
                    effects =
                        MutableSharedFlow(
                            replay = 16,
                            onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        ),
                ),
            serviceStateStore = serviceStateStore,
            serviceController = serviceController,
            serviceIntentArbiter = serviceIntentArbiter,
        )
}
