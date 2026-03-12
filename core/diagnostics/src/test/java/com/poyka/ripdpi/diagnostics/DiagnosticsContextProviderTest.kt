package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.DefaultServiceStateStore
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceTelemetrySnapshot
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
            val history =
                object : DiagnosticsHistoryRepository {
                    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = MutableStateFlow(emptyList())

                    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> = MutableStateFlow(emptyList())

                    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = MutableStateFlow(emptyList())

                    override fun observeConnectionSnapshots(
                        connectionSessionId: String,
                        limit: Int,
                    ): Flow<List<NetworkSnapshotEntity>> = MutableStateFlow(emptyList())

                    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> = MutableStateFlow(emptyList())

                    override fun observeConnectionContexts(
                        connectionSessionId: String,
                        limit: Int,
                    ): Flow<List<DiagnosticContextEntity>> = MutableStateFlow(emptyList())

                    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = MutableStateFlow(emptyList())

                    override fun observeConnectionTelemetry(
                        connectionSessionId: String,
                        limit: Int,
                    ): Flow<List<TelemetrySampleEntity>> = MutableStateFlow(emptyList())

                    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = MutableStateFlow(emptyList())

                    override fun observeConnectionNativeEvents(
                        connectionSessionId: String,
                        limit: Int,
                    ): Flow<List<NativeSessionEventEntity>> = MutableStateFlow(emptyList())

                    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = MutableStateFlow(emptyList())

                    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> =
                        MutableStateFlow(emptyList())

                    override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
                        MutableStateFlow(emptyList())

                    override suspend fun getProfile(id: String): DiagnosticProfileEntity? =
                        DiagnosticProfileEntity(
                            id = "default",
                            name = "Default",
                            source = "bundled",
                            version = 1,
                            requestJson = "{}",
                            updatedAt = 1L,
                        )

                    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = null

                    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? = null

                    override suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity? = null

                    override suspend fun getRememberedNetworkPolicy(
                        fingerprintHash: String,
                        mode: String,
                    ): RememberedNetworkPolicyEntity? = null

                    override suspend fun findValidatedRememberedNetworkPolicy(
                        fingerprintHash: String,
                        mode: String,
                        now: Long,
                    ): RememberedNetworkPolicyEntity? = null

                    override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> = emptyList()

                    override suspend fun upsertProfile(profile: DiagnosticProfileEntity) = Unit

                    override suspend fun upsertPackVersion(version: TargetPackVersionEntity) = Unit

                    override suspend fun upsertScanSession(session: ScanSessionEntity) = Unit

                    override suspend fun replaceProbeResults(sessionId: String, results: List<ProbeResultEntity>) = Unit

                    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) = Unit

                    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) = Unit

                    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) = Unit

                    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) = Unit

                    override suspend fun insertExportRecord(record: ExportRecordEntity) = Unit

                    override suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity) = Unit

                    override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long = policy.id

                    override suspend fun trimOldData(retentionDays: Int) = Unit

                    override suspend fun clearRememberedNetworkPolicies() = Unit

                    override suspend fun pruneRememberedNetworkPolicies() = Unit
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
                    historyRepository = history,
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
