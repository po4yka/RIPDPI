package com.poyka.ripdpi.automation

import com.poyka.ripdpi.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationLaunchContractTest {
    @Test
    fun `intent extras override instrumentation args`() {
        val config =
            resolveAutomationLaunchConfig(
                instrumentationArgs =
                    mapOf(
                        AutomationLaunchContract.Enabled to "true",
                        AutomationLaunchContract.StartRoute to Route.Home.route,
                        AutomationLaunchContract.DisableMotion to "false",
                        AutomationLaunchContract.PermissionPreset to AutomationPermissionPreset.Granted.wireValue,
                        AutomationLaunchContract.ServicePreset to AutomationServicePreset.Idle.wireValue,
                        AutomationLaunchContract.DataPreset to AutomationDataPreset.CleanHome.wireValue,
                    ),
                intentArgs =
                    mapOf(
                        AutomationLaunchContract.Enabled to true,
                        AutomationLaunchContract.StartRoute to Route.AdvancedSettings.route,
                        AutomationLaunchContract.DisableMotion to true,
                        AutomationLaunchContract.PermissionPreset to AutomationPermissionPreset.VpnMissing.wireValue,
                        AutomationLaunchContract.ServicePreset to AutomationServicePreset.ConnectedVpn.wireValue,
                        AutomationLaunchContract.DataPreset to AutomationDataPreset.SettingsReady.wireValue,
                    ),
            )

        assertEquals(
            AutomationLaunchConfig(
                enabled = true,
                startRoute = Route.AdvancedSettings.route,
                disableMotion = true,
                permissionPreset = AutomationPermissionPreset.VpnMissing,
                servicePreset = AutomationServicePreset.ConnectedVpn,
                dataPreset = AutomationDataPreset.SettingsReady,
            ),
            config,
        )
    }

    @Test
    fun `unknown values fall back to defaults and invalid route is ignored`() {
        val config =
            resolveAutomationLaunchConfig(
                instrumentationArgs =
                    mapOf(
                        AutomationLaunchContract.Enabled to "true",
                        AutomationLaunchContract.StartRoute to "not_a_route",
                        AutomationLaunchContract.PermissionPreset to "invalid_permission",
                        AutomationLaunchContract.ServicePreset to "invalid_service",
                        AutomationLaunchContract.DataPreset to "invalid_data",
                    ),
            )

        assertEquals(true, config.enabled)
        assertNull(config.startRoute)
        assertEquals(AutomationPermissionPreset.Granted, config.permissionPreset)
        assertEquals(AutomationServicePreset.Idle, config.servicePreset)
        assertEquals(AutomationDataPreset.CleanHome, config.dataPreset)
    }

    @Test
    fun `disabled automation ignores auxiliary values`() {
        val config =
            resolveAutomationLaunchConfig(
                instrumentationArgs =
                    mapOf(
                        AutomationLaunchContract.Enabled to "false",
                        AutomationLaunchContract.ResetState to "true",
                        AutomationLaunchContract.StartRoute to Route.Diagnostics.route,
                        AutomationLaunchContract.DisableMotion to "true",
                    ),
            )

        assertFalse(config.enabled)
        assertEquals(AutomationLaunchConfig(), config)
    }
}
