package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import com.poyka.ripdpi.platform.StringResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPermissionSummaryTest {
    private val stringResolver = FakeStringResolver()

    @Test
    fun `battery banner shown when not dismissed and status requires settings`() {
        val summary =
            buildPermissionSummary(
                snapshot = PermissionSnapshot(batteryOptimization = PermissionStatus.RequiresSettings),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = stringResolver,
                deviceManufacturer = "Google",
                batteryBannerDismissed = false,
            )
        assertNotNull(summary.recommendedIssue)
        assert(summary.recommendedIssue?.kind == PermissionKind.BatteryOptimization)
    }

    @Test
    fun `battery banner hidden when dismissed`() {
        val summary =
            buildPermissionSummary(
                snapshot = PermissionSnapshot(batteryOptimization = PermissionStatus.RequiresSettings),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = stringResolver,
                deviceManufacturer = "Google",
                batteryBannerDismissed = true,
            )
        assertNull(summary.recommendedIssue)
    }

    @Test
    fun `battery banner hidden when already granted regardless of dismiss flag`() {
        val summary =
            buildPermissionSummary(
                snapshot = PermissionSnapshot(batteryOptimization = PermissionStatus.Granted),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = stringResolver,
                deviceManufacturer = "Google",
                batteryBannerDismissed = false,
            )
        assertNull(summary.recommendedIssue)
    }

    @Test
    fun `background guidance shown when not dismissed`() {
        val summary =
            buildPermissionSummary(
                snapshot = PermissionSnapshot(),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = stringResolver,
                deviceManufacturer = "Google",
                backgroundGuidanceDismissed = false,
            )
        assertNotNull(summary.backgroundGuidance)
    }

    @Test
    fun `background guidance hidden when dismissed`() {
        val summary =
            buildPermissionSummary(
                snapshot = PermissionSnapshot(),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = stringResolver,
                deviceManufacturer = "Google",
                backgroundGuidanceDismissed = true,
            )
        assertNull(summary.backgroundGuidance)
    }

    @Test
    fun `permission summary distinguishes vpn consent always-on lockdown and battery health`() {
        val summary =
            buildPermissionSummary(
                snapshot =
                    PermissionSnapshot(
                        vpnConsent = PermissionStatus.Granted,
                        alwaysOnVpn = PermissionStatus.RequiresSettings,
                        vpnLockdown = PermissionStatus.Unknown,
                        batteryOptimization = PermissionStatus.RequiresSettings,
                    ),
                issue = null,
                configuredMode = Mode.VPN,
                stringResolver = stringResolver,
                deviceManufacturer = "Google",
                backgroundGuidanceDismissed = true,
            )
        val kinds = summary.items.map { it.kind }

        assertTrue(PermissionKind.VpnConsent in kinds)
        assertTrue(PermissionKind.AlwaysOnVpn in kinds)
        assertTrue(PermissionKind.VpnLockdown in kinds)
        assertTrue(PermissionKind.BatteryOptimization in kinds)
    }

    @Test
    fun `notification permission copy is recommended not required`() {
        val resourceStringResolver = NotificationCopyStringResolver()

        val notificationItem =
            buildNotificationPermissionItem(
                status = PermissionStatus.RequiresSystemPrompt,
                stringResolver = resourceStringResolver,
            )
        val notificationIssue =
            createPermissionIssue(
                kind = PermissionKind.Notifications,
                status = PermissionStatus.RequiresSystemPrompt,
                blocking = false,
                stringResolver = resourceStringResolver,
            )

        assertEquals("Recommended", notificationItem.statusLabel)
        assertFalse(notificationItem.subtitle.contains("Required", ignoreCase = true))
        assertFalse(notificationIssue.message.contains("required", ignoreCase = true))
        assertFalse(notificationIssue.blocking)
    }

    private class NotificationCopyStringResolver : StringResolver {
        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String =
            when (resId) {
                R.string.settings_permission_status_recommended -> {
                    "Recommended"
                }

                R.string.settings_permissions_notifications_needed -> {
                    "Recommended so Android can show status and service alerts for the local VPN or proxy."
                }

                R.string.permissions_notifications_denied -> {
                    "Allow notifications to show RIPDPI status and service alerts while " +
                        "the local VPN or proxy is running."
                }

                R.string.permissions_notifications_title -> {
                    "Allow status notifications"
                }

                R.string.settings_permission_action_allow -> {
                    "Allow"
                }

                else -> {
                    resId.toString()
                }
            }
    }
}
