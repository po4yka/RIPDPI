package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
            val profileCatalog =
                FakeDiagnosticsHistoryStores().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "default",
                                name = "Default",
                                source = "bundled",
                                version = 1,
                                requestJson = "{}",
                                updatedAt = 1L,
                            ),
                        )
                }
            val settingsRepository =
                object : AppSettingsRepository {
                    private val settingsState =
                        MutableStateFlow(
                            AppSettings
                                .newBuilder()
                                .setRipdpiMode("vpn")
                                .setDiagnosticsActiveProfileId("default")
                                .setProxyIp("127.0.0.1")
                                .setProxyPort(1080)
                                .setDesyncMethod("split")
                                .build(),
                        )

                    override val settings: Flow<AppSettings> = settingsState

                    override suspend fun snapshot(): AppSettings = settingsState.value

                    override suspend fun update(transform: AppSettings.Builder.() -> Unit) = Unit

                    override suspend fun replace(settings: AppSettings) = Unit
                }
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
}
