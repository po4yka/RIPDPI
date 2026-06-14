package com.poyka.ripdpi.ui.screens.detection

import android.Manifest
import com.poyka.ripdpi.core.detection.DetectionPermissionPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetectionPermissionStateOwnerTest {
    private class Harness(
        val flow: MutableStateFlow<DetectionCheckUiState>,
        val prefs: FakeDetectionCheckPreferenceStore,
        val owner: DetectionPermissionStateOwner,
    )

    private fun harness(
        granted: Set<String> = emptySet(),
        requested: Set<String> = emptySet(),
    ): Harness {
        val flow = MutableStateFlow(DetectionCheckUiState())
        val prefs = FakeDetectionCheckPreferenceStore(requestedPermissions = requested)
        val stateOwner =
            DetectionPermissionStateOwner(
                reducer = DetectionCheckStateReducer(flow),
                permissionChecker = FakeDetectionPermissionChecker(granted = granted),
                preferences = prefs,
            )
        return Harness(flow, prefs, stateOwner)
    }

    @Test
    @Config(sdk = [33])
    fun `requiredPermissions includes nearby wifi on tiramisu and above`() {
        val perms = DetectionPermissionStateOwner.requiredPermissions().toList()
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in perms)
        assertEquals(3, perms.size)
    }

    @Test
    @Config(sdk = [30])
    fun `requiredPermissions omits nearby wifi below tiramisu`() {
        val perms = DetectionPermissionStateOwner.requiredPermissions().toList()
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in perms)
        assertEquals(2, perms.size)
    }

    @Test
    @Config(sdk = [33])
    fun `refreshPermissionState reports missing for ungranted permissions`() {
        val h = harness(granted = emptySet())
        h.owner.refreshPermissionState()
        assertEquals(
            DetectionPermissionStateOwner.requiredPermissions().toSet(),
            h.flow.value.missingPermissions
                .toSet(),
        )
        assertTrue(h.flow.value.permissionAction != DetectionPermissionPlanner.Action.NONE)
    }

    @Test
    @Config(sdk = [33])
    fun `refreshPermissionState reports none missing when all granted`() {
        val granted = DetectionPermissionStateOwner.requiredPermissions().toSet()
        val h = harness(granted = granted)
        h.owner.refreshPermissionState()
        assertTrue(
            h.flow.value.missingPermissions
                .isEmpty(),
        )
        assertEquals(DetectionPermissionPlanner.Action.NONE, h.flow.value.permissionAction)
    }

    @Test
    @Config(sdk = [33])
    fun `onPermissionsResult marks all requested then refreshes`() {
        val granted = DetectionPermissionStateOwner.requiredPermissions().toSet()
        val h = harness(granted = granted)
        h.owner.onPermissionsResult()
        assertEquals(granted, h.prefs.requested)
        assertTrue(
            h.flow.value.missingPermissions
                .isEmpty(),
        )
    }
}
