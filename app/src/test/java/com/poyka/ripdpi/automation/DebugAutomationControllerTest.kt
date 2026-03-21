package com.poyka.ripdpi.automation

import android.content.Intent
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.activities.FakeServiceStateStore
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAutomationControllerTest {
    @After
    fun tearDown() {
        System.clearProperty("ripdpi.staticMotion")
    }

    @Test
    fun `prepareLaunch seeds settings preset and static motion`() = runTest {
        val repository = FakeAppSettingsRepository()
        val serviceStateStore = FakeServiceStateStore()
        val controller = DebugAutomationController(repository, serviceStateStore)

        controller.prepareLaunch(
            automationIntent(
                disableMotion = true,
                dataPreset = AutomationDataPreset.SettingsReady,
                servicePreset = AutomationServicePreset.ConnectedProxy,
            ),
        )

        val settings = repository.snapshot()
        assertTrue(settings.onboardingComplete)
        assertFalse(settings.biometricEnabled)
        assertEquals("cloudflare", settings.dnsProviderId)
        assertTrue(settings.webrtcProtectionEnabled)
        assertEquals(AppStatus.Running to Mode.Proxy, serviceStateStore.status.value)
        assertEquals("true", System.getProperty("ripdpi.staticMotion"))
    }

    @Test
    fun `permission preset overrides provider snapshot`() {
        val controller = DebugAutomationController(FakeAppSettingsRepository(), FakeServiceStateStore())

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
    fun `fake start and stop mutate service state when preset is not live`() {
        val serviceStateStore = FakeServiceStateStore()
        val controller = DebugAutomationController(FakeAppSettingsRepository(), serviceStateStore)

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

    private fun automationIntent(
        disableMotion: Boolean = false,
        permissionPreset: AutomationPermissionPreset = AutomationPermissionPreset.Granted,
        servicePreset: AutomationServicePreset = AutomationServicePreset.Idle,
        dataPreset: AutomationDataPreset = AutomationDataPreset.CleanHome,
    ): Intent =
        Intent().apply {
            putExtra(AutomationLaunchContract.Enabled, true)
            putExtra(AutomationLaunchContract.ResetState, true)
            putExtra(AutomationLaunchContract.DisableMotion, disableMotion)
            putExtra(AutomationLaunchContract.PermissionPreset, permissionPreset.wireValue)
            putExtra(AutomationLaunchContract.ServicePreset, servicePreset.wireValue)
            putExtra(AutomationLaunchContract.DataPreset, dataPreset.wireValue)
        }
}
