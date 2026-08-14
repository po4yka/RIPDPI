package com.poyka.ripdpi.automation

import android.content.Intent
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugAutomationControllerTest {
    @After
    fun tearDown() {
        System.clearProperty("ripdpi.staticMotion")
    }

    @Test
    fun `prepareLaunch seeds settings preset and static motion`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val serviceStateStore = FakeServiceStateStore()
            val controller = newController(repository, serviceStateStore)

            controller.prepareLaunch(
                automationIntent(
                    disableMotion = true,
                    dataPreset = AutomationDataPreset.SettingsReady,
                    servicePreset = AutomationServicePreset.ConnectedProxy,
                    themePreset = AutomationThemePreset.Dark,
                ),
            )

            val settings = repository.snapshot()
            assertTrue(settings.onboardingComplete)
            assertFalse(settings.biometricEnabled)
            assertEquals("cloudflare", settings.dnsProviderId)
            assertEquals("dark", settings.appTheme)
            assertTrue(settings.webrtcProtectionEnabled)
            assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
            assertEquals("true", System.getProperty("ripdpi.staticMotion"))
        }

    @Test
    fun `permission preset overrides provider snapshot`() {
        val controller = newController()

        controller.prepareLaunch(
            automationIntent(
                permissionPreset = AutomationPermissionPreset.NotificationsMissing,
            ),
        )

        val snapshot = controller.currentPermissionSnapshot(PermissionSnapshot())
        assertEquals(PermissionStatus.Granted, snapshot.vpnConsent)
        assertEquals(PermissionStatus.RequiresSystemPrompt, snapshot.notifications)
        assertEquals(PermissionStatus.Granted, snapshot.batteryOptimization)
    }

    @Test
    fun `biometric locked preset seeds the app lock gate`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val controller = newController(repository)

            controller.prepareLaunch(
                automationIntent(
                    dataPreset = AutomationDataPreset.BiometricLocked,
                ),
            )

            val settings = repository.snapshot()
            assertTrue(settings.onboardingComplete)
            assertTrue(settings.biometricEnabled)
            assertEquals("", settings.backupPin)
            assertEquals("cloudflare", settings.dnsProviderId)
            assertTrue(settings.webrtcProtectionEnabled)
        }

    @Test
    fun `biometric locked with pin preset seeds backup pin fallback`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val controller = newController(repository)

            controller.prepareLaunch(
                automationIntent(
                    dataPreset = AutomationDataPreset.BiometricLockedWithPin,
                ),
            )

            val settings = repository.snapshot()
            assertTrue(settings.onboardingComplete)
            assertTrue(settings.biometricEnabled)
            assertEquals("0000", settings.backupPin)
            assertEquals("cloudflare", settings.dnsProviderId)
        }

    @Test
    fun `diagnostics report preset seeds completed report session`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val scanRecordStore = FakeDiagnosticsScanRecordStore()
            val controller =
                newController(
                    appSettingsRepository = repository,
                    scanRecordStore = scanRecordStore,
                )

            controller.prepareLaunch(
                automationIntent(
                    dataPreset = AutomationDataPreset.DiagnosticsReportDemo,
                    servicePreset = AutomationServicePreset.ConnectedVpn,
                ),
            )

            val settings = repository.snapshot()
            val session = scanRecordStore.getScanSession("automation-report-demo-session")
            assertEquals("automatic-audit", settings.diagnosticsActiveProfileId)
            assertEquals("automatic-audit", session?.profileId)
            assertEquals("completed", session?.status)
            assertNotNull(session?.reportJson)
            assertEquals(2, scanRecordStore.getProbeResults("automation-report-demo-session").size)
        }

    @Test
    fun `reset state clears diagnostics runtime history before seeding preset`() =
        runTest {
            val resetStore = FakeDiagnosticsHistoryResetStore()
            val controller = newController(historyResetStore = resetStore)

            controller.prepareLaunch(
                automationIntent(
                    dataPreset = AutomationDataPreset.CleanHome,
                ),
            )

            assertEquals(1, resetStore.clearCount)
        }

    @Test
    fun `clean home preset keeps onboarding reachable`() =
        runTest {
            val repository = FakeAppSettingsRepository()
            val controller = newController(repository)

            controller.prepareLaunch(
                automationIntent(
                    dataPreset = AutomationDataPreset.CleanHome,
                ),
            )

            assertFalse(repository.snapshot().onboardingComplete)
        }

    @Test
    fun `fake start and stop mutate service state when preset is not live`() {
        val serviceStateStore = FakeServiceStateStore()
        val controller = newController(serviceStateStore = serviceStateStore)

        controller.prepareLaunch(
            automationIntent(
                servicePreset = AutomationServicePreset.Idle,
            ),
        )

        assertTrue(controller.interceptStart(Mode.VPN))
        assertEquals(AppStatus.Running to Mode.VPN, serviceStateStore.status.value)

        assertTrue(controller.interceptStop(Mode.VPN))
        assertEquals(AppStatus.Halted to Mode.VPN, serviceStateStore.status.value)
    }

    @Test
    fun `live service preset preserves real service state`() =
        runTest {
            val serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy)
            val controller = newController(serviceStateStore = serviceStateStore)

            controller.prepareLaunch(
                automationIntent(
                    servicePreset = AutomationServicePreset.Live,
                ),
            )

            assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
            assertFalse(controller.interceptStart(Mode.VPN))
            assertFalse(controller.interceptStop(Mode.Proxy))
            assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
        }

    private fun automationIntent(
        disableMotion: Boolean = false,
        permissionPreset: AutomationPermissionPreset = AutomationPermissionPreset.Granted,
        servicePreset: AutomationServicePreset = AutomationServicePreset.Idle,
        dataPreset: AutomationDataPreset = AutomationDataPreset.CleanHome,
        themePreset: AutomationThemePreset = AutomationThemePreset.System,
    ): Intent =
        Intent().apply {
            putExtra(AutomationLaunchContract.Enabled, true)
            putExtra(AutomationLaunchContract.ResetState, true)
            putExtra(AutomationLaunchContract.DisableMotion, disableMotion)
            putExtra(AutomationLaunchContract.PermissionPreset, permissionPreset.wireValue)
            putExtra(AutomationLaunchContract.ServicePreset, servicePreset.wireValue)
            putExtra(AutomationLaunchContract.DataPreset, dataPreset.wireValue)
            putExtra(AutomationLaunchContract.Theme, themePreset.wireValue)
        }

    private fun newController(
        appSettingsRepository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        serviceStateStore: FakeServiceStateStore = FakeServiceStateStore(),
        scanRecordStore: FakeDiagnosticsScanRecordStore = FakeDiagnosticsScanRecordStore(),
        historyResetStore: FakeDiagnosticsHistoryResetStore = FakeDiagnosticsHistoryResetStore(),
    ): DebugAutomationController =
        DebugAutomationController(
            appSettingsRepository = appSettingsRepository,
            serviceStateStore = serviceStateStore,
            scanRecordStore = scanRecordStore,
            historyResetStore = historyResetStore,
            diagnosticsJson =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
        )

    private class FakeDiagnosticsHistoryResetStore : DiagnosticsHistoryResetStore {
        var clearCount = 0

        override suspend fun clearRuntimeHistory() {
            clearCount += 1
        }
    }

    private class FakeDiagnosticsScanRecordStore : DiagnosticsScanRecordStore {
        private val sessions = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
        private val probeResults = mutableMapOf<String, List<ProbeResultEntity>>()

        override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> =
            sessions.map { values -> values.take(limit) }

        override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
            sessions.value.firstOrNull { it.id == sessionId }

        override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
            probeResults[sessionId].orEmpty()

        override suspend fun upsertScanSession(session: ScanSessionEntity) {
            sessions.value =
                (sessions.value.filterNot { it.id == session.id } + session)
                    .sortedByDescending { it.startedAt }
        }

        override suspend fun replaceProbeResults(
            sessionId: String,
            results: List<ProbeResultEntity>,
        ) {
            probeResults[sessionId] = results
        }
    }
}
