package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCoordinatorTest {
    private val coordinator = PermissionCoordinator()

    @Test
    fun `vpn start resolves notifications before vpn consent`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.StartConfiguredMode,
                configuredMode = Mode.VPN,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        notifications = PermissionStatus.RequiresSystemPrompt,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    ),
            )

        assertEquals(
            listOf(PermissionKind.Notifications, PermissionKind.VpnConsent),
            resolution.required,
        )
        assertEquals(PermissionKind.Notifications, resolution.blockedBy)
        assertEquals(listOf(PermissionKind.BatteryOptimization), resolution.recommended)
    }

    @Test
    fun `proxy start is not blocked by vpn consent`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.StartConfiguredMode,
                configuredMode = Mode.Proxy,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.NotApplicable,
                    ),
            )

        assertTrue(resolution.required.isEmpty())
        assertEquals(null, resolution.blockedBy)
    }

    @Test
    fun `battery optimization repair targets only that permission`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.RepairPermission(PermissionKind.BatteryOptimization),
                configuredMode = Mode.VPN,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    ),
            )

        assertEquals(listOf(PermissionKind.BatteryOptimization), resolution.required)
        assertEquals(PermissionKind.BatteryOptimization, resolution.blockedBy)
    }
}
