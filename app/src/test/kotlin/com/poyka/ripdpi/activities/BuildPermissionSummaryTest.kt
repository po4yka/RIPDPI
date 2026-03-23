package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.permissions.PermissionKind
import com.poyka.ripdpi.permissions.PermissionSnapshot
import com.poyka.ripdpi.permissions.PermissionStatus
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BuildPermissionSummaryTest {
    private val stringResolver = FakeStringResolver()

    @Test
    fun `battery banner shown when not dismissed and status requires settings`() {
        val summary = buildPermissionSummary(
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
        val summary = buildPermissionSummary(
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
        val summary = buildPermissionSummary(
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
        val summary = buildPermissionSummary(
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
        val summary = buildPermissionSummary(
            snapshot = PermissionSnapshot(),
            issue = null,
            configuredMode = Mode.VPN,
            stringResolver = stringResolver,
            deviceManufacturer = "Google",
            backgroundGuidanceDismissed = true,
        )
        assertNull(summary.backgroundGuidance)
    }
}
