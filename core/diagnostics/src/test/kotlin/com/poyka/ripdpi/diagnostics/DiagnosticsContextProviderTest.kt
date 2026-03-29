package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsContextProviderTest {
    @Test
    fun `provider captures app device and service context`() =
        runTest {
            val json = diagnosticsTestJson()
            val profileCatalog = diagnosticsProfileCatalog(json)
            val settingsRepository = diagnosticsSettingsRepository()
            val serviceStateStore: ServiceStateStore =
                DefaultServiceStateStore().apply {
                    setStatus(AppStatus.Running, Mode.VPN)
                    updateTelemetry(
                        ServiceTelemetrySnapshot(
                            mode = Mode.VPN,
                            status = AppStatus.Running,
                            restartCount = 3,
                            updatedAt = 10L,
                        ),
                    )
                }

            val provider =
                AndroidDiagnosticsContextProvider(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = settingsRepository,
                    profileCatalog = profileCatalog,
                    serviceStateStore = serviceStateStore,
                )

            val context = provider.captureContext()

            assertEquals("Running", context.service.serviceStatus)
            assertEquals("Default", context.service.selectedProfileName)
            assertEquals("split", context.service.desyncMethod)
            assertTrue(context.device.appVersionName.isNotBlank())
            assertTrue(context.device.model.isNotBlank())
            assertTrue(context.permissions.vpnPermissionState in setOf("enabled", "disabled"))
            assertTrue(context.environment.networkMeteredState in setOf("enabled", "disabled", "unknown"))
        }

    @Test
    fun `booleanState returns unknown for null`() {
        assertEquals("unknown", booleanState(null))
        assertEquals("enabled", booleanState(true))
        assertEquals("disabled", booleanState(false))
    }

    @Test
    fun `provider captures blocked host telemetry details in service context`() =
        runTest {
            val json = diagnosticsTestJson()
            val profileCatalog = diagnosticsProfileCatalog(json)
            val settingsRepository = diagnosticsSettingsRepository()
            val serviceStateStore: ServiceStateStore =
                DefaultServiceStateStore().apply {
                    setStatus(AppStatus.Running, Mode.VPN)
                    updateTelemetry(
                        ServiceTelemetrySnapshot(
                            mode = Mode.VPN,
                            status = AppStatus.Running,
                            restartCount = 3,
                            updatedAt = 10L,
                            proxyTelemetry =
                                NativeRuntimeSnapshot(
                                    source = "proxy",
                                    autolearnEnabled = true,
                                    learnedHostCount = 4,
                                    penalizedHostCount = 2,
                                    blockedHostCount = 1,
                                    lastBlockSignal = "tcp_reset",
                                    lastBlockProvider = "rkn",
                                    lastAutolearnHost = "blocked.example",
                                    lastAutolearnGroup = 2,
                                    lastAutolearnAction = "host_blocked",
                                ),
                        ),
                    )
                }

            val provider =
                AndroidDiagnosticsContextProvider(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = settingsRepository,
                    profileCatalog = profileCatalog,
                    serviceStateStore = serviceStateStore,
                )

            val context = provider.captureContext()

            assertEquals("enabled", context.service.hostAutolearnEnabled)
            assertEquals(4, context.service.learnedHostCount)
            assertEquals(2, context.service.penalizedHostCount)
            assertEquals(1, context.service.blockedHostCount)
            assertEquals("tcp_reset", context.service.lastBlockSignal)
            assertEquals("rkn", context.service.lastBlockProvider)
            assertEquals("blocked.example", context.service.lastAutolearnHost)
            assertEquals("2", context.service.lastAutolearnGroup)
            assertEquals("host_blocked", context.service.lastAutolearnAction)
        }

    private fun diagnosticsProfileCatalog(json: Json) =
        FakeDiagnosticsHistoryStores().apply {
            profilesState.value =
                listOf(
                    DiagnosticProfileEntity(
                        id = "default",
                        name = "Default",
                        source = "bundled",
                        version = 1,
                        requestJson =
                            diagnosticsProfileRequestJson(
                                json = json,
                                profileId = "default",
                                displayName = "Default",
                            ),
                        updatedAt = 1L,
                    ),
                )
        }

    private fun diagnosticsSettingsRepository(): AppSettingsRepository =
        object : AppSettingsRepository {
            private val settingsState =
                MutableStateFlow(
                    AppSettings
                        .newBuilder()
                        .setRipdpiMode("vpn")
                        .setDiagnosticsActiveProfileId("default")
                        .setProxyIp("127.0.0.1")
                        .setProxyPort(1080)
                        .setStrategyChains(
                            tcpSteps = listOf(TcpChainStepModel(TcpChainStepKind.Split, "host+1")),
                            udpSteps = emptyList(),
                        ).build(),
                )

            override val settings: Flow<AppSettings> = settingsState

            override suspend fun snapshot(): AppSettings = settingsState.value

            override suspend fun update(transform: AppSettings.Builder.() -> Unit) = Unit

            override suspend fun replace(settings: AppSettings) = Unit
        }
}
