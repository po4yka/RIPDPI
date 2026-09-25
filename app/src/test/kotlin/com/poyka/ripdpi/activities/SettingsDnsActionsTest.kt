package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoq
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.Mode
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
    fun `custom doq save preserves protocol and endpoint`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val actions = createActions(repository = repository)

            actions.setCustomDotResolver(
                EncryptedDnsProtocolDoq,
                "quic.example",
                853,
                "quic.example",
                listOf("1.1.1.1"),
            )
            advanceUntilIdle()

            val settings = repository.snapshot()
            assertEquals(EncryptedDnsProtocolDoq, settings.encryptedDnsProtocol)
            assertEquals("quic.example", settings.encryptedDnsHost)
            assertEquals(853, settings.encryptedDnsPort)
            assertEquals("quic.example", settings.encryptedDnsTlsServerName)
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
                    configsHex = "0102",
                    configsRetrievedAtSecs = 1_700_000_000,
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
                configsHex = "0102",
                configsRetrievedAtSecs = 1_700_000_000,
                configsTtlSecs = 86_400,
            )
        assertThrows(IllegalArgumentException::class.java) {
            actions.setCustomOdohResolver(fields, listOf("1.1.1.1"))
        }
    }

    private fun createActions(
        repository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        serviceController: FakeServiceController = FakeServiceController(),
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
        )
}
