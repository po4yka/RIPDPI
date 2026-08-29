package com.poyka.ripdpi.permissions

import com.poyka.ripdpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCoordinatorTest {
    private val coordinator = PermissionCoordinator()

    @Test
    fun `LAN denial does not block public VPN but explicit repair requests LAN only`() {
        val snapshot =
            PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                localNetwork = PermissionStatus.Denied,
            )
        val start = coordinator.resolve(PermissionAction.StartVpnMode, Mode.VPN, snapshot)
        assertTrue(start.required.isEmpty())
        val repair =
            coordinator.resolve(
                PermissionAction.RepairPermission(PermissionKind.LocalNetwork),
                Mode.VPN,
                snapshot,
            )
        assertEquals(listOf(PermissionKind.LocalNetwork), repair.required)
    }

    @Test
    fun `vpn start requires vpn consent and recommends notifications`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.StartConfiguredMode,
                configuredMode = Mode.VPN,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.RequiresSystemPrompt,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    ),
            )

        assertEquals(
            listOf(PermissionKind.VpnConsent),
            resolution.required,
        )
        assertEquals(PermissionKind.VpnConsent, resolution.blockedBy)
        assertEquals(
            listOf(PermissionKind.Notifications, PermissionKind.BatteryOptimization),
            resolution.recommended,
        )
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
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.NotApplicable,
                    ),
            )

        assertTrue(resolution.required.isEmpty())
        assertEquals(null, resolution.blockedBy)
    }

    @Test
    fun `explicit proxy start is not blocked by configured vpn consent`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.StartProxyMode,
                configuredMode = Mode.VPN,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.NotApplicable,
                    ),
            )

        assertTrue(resolution.required.isEmpty())
        assertEquals(null, resolution.blockedBy)
    }

    @Test
    fun `explicit vpn start requires vpn consent independent of configured proxy mode`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.StartVpnMode,
                configuredMode = Mode.Proxy,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.RequiresSystemPrompt,
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.NotApplicable,
                    ),
            )

        assertEquals(listOf(PermissionKind.VpnConsent), resolution.required)
        assertEquals(PermissionKind.VpnConsent, resolution.blockedBy)
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
                        alwaysOnVpn = PermissionStatus.Granted,
                        vpnLockdown = PermissionStatus.Granted,
                        notifications = PermissionStatus.Granted,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    ),
            )

        assertEquals(listOf(PermissionKind.BatteryOptimization), resolution.required)
        assertEquals(PermissionKind.BatteryOptimization, resolution.blockedBy)
    }

    @Test
    fun `vpn start recommends notifications always-on lockdown and battery without blocking startup`() {
        val resolution =
            coordinator.resolve(
                action = PermissionAction.StartVpnMode,
                configuredMode = Mode.Proxy,
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        alwaysOnVpn = PermissionStatus.RequiresSettings,
                        vpnLockdown = PermissionStatus.Unknown,
                        notifications = PermissionStatus.RequiresSystemPrompt,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    ),
            )

        assertTrue(resolution.required.isEmpty())
        assertEquals(
            listOf(
                PermissionKind.Notifications,
                PermissionKind.AlwaysOnVpn,
                PermissionKind.VpnLockdown,
                PermissionKind.BatteryOptimization,
            ),
            resolution.recommended,
        )
    }
}
