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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetectionPermissionStateOwnerTest {
    private class Harness(
        val flow: MutableStateFlow<DetectionCheckUiState>,
        val checker: FakeDetectionPermissionChecker,
        val owner: DetectionPermissionStateOwner,
    )

    private fun harness(
        granted: Set<String> = emptySet(),
        denied: Set<String> = emptySet(),
        onboardingShown: Boolean = false,
    ): Harness {
        val flow = MutableStateFlow(DetectionCheckUiState())
        val prefs = FakeDetectionCheckPreferenceStore(onboardingShown, deniedPermissions = denied)
        val checker = FakeDetectionPermissionChecker(granted = granted)
        val stateOwner =
            DetectionPermissionStateOwner(
                reducer = DetectionCheckStateReducer(flow),
                permissionChecker = checker,
                preferences = prefs,
            )
        return Harness(flow, checker, stateOwner)
    }

    @Test
    @Config(sdk = [30])
    fun `detection signals require phone and precise location`() {
        assertEquals(
            setOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            DetectionPermissionStateOwner.requiredPermissions().toSet(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun `common manifest declares every detection runtime permission`() {
        val declaredPermissions =
            Regex("""<uses-permission\s+[^>]*android:name="([^"]+)"""")
                .findAll(commonManifestFile().readText())
                .map { match -> match.groupValues[1] }
                .toSet()
        val h = harness()
        h.owner.refreshPermissionState { false }
        val missingPermissions =
            h.flow.value.permissionRequest
                .toSet() - declaredPermissions

        assertTrue(
            "Common manifest is missing Detection permissions: $missingPermissions",
            missingPermissions.isEmpty(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun `first request pairs precise with approximate location`() {
        val h = harness()
        h.owner.refreshPermissionState { false }

        assertEquals(
            setOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            h.flow.value.permissionRequest
                .toSet(),
        )
        assertEquals(DetectionPermissionPlanner.Action.REQUEST, h.flow.value.permissionAction)
    }

    @Test
    @Config(sdk = [33])
    fun `refreshPermissionState reports none missing when all granted`() {
        val granted = DetectionPermissionStateOwner.requiredPermissions().toSet()
        val h = harness(granted = granted)
        h.owner.refreshPermissionState { false }
        assertTrue(
            h.flow.value.permissionRequest
                .isEmpty(),
        )
        assertEquals(DetectionPermissionPlanner.Action.NONE, h.flow.value.permissionAction)
    }

    @Test
    @Config(sdk = [31])
    fun `approximate grant keeps precise upgrade available and does not require a second phone grant`() {
        val h =
            harness(granted = setOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_COARSE_LOCATION))
        h.owner.onPermissionsResult(
            mapOf(
                Manifest.permission.READ_PHONE_STATE to true,
                Manifest.permission.ACCESS_COARSE_LOCATION to true,
                Manifest.permission.ACCESS_FINE_LOCATION to false,
            ),
        ) { false }

        assertEquals(DetectionPermissionPlanner.Action.REQUEST, h.flow.value.permissionAction)
        assertEquals(
            setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            h.flow.value.permissionRequest
                .toSet(),
        )
    }

    @Test
    @Config(sdk = [30])
    fun `first request on Android 11 asks for precise location without approximate companion`() {
        val h = harness(granted = setOf(Manifest.permission.READ_PHONE_STATE))
        h.owner.refreshPermissionState { false }
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), h.flow.value.permissionRequest)
    }

    @Test
    @Config(sdk = [33])
    fun `empty and ambiguous dismissed results leave permissions requestable`() {
        val h = harness()
        h.owner.onPermissionsResult(emptyMap()) { false }
        assertEquals(DetectionPermissionPlanner.Action.REQUEST, h.flow.value.permissionAction)
        assertFalse(h.flow.value.canOpenPermissionSettings)

        h.owner.onPermissionsResult(mapOf(Manifest.permission.READ_PHONE_STATE to false)) { false }
        assertEquals(DetectionPermissionPlanner.Action.REQUEST, h.flow.value.permissionAction)
        assertTrue(Manifest.permission.READ_PHONE_STATE in h.flow.value.permissionRequest)
        assertTrue(h.flow.value.canOpenPermissionSettings)
    }

    @Test
    @Config(sdk = [33])
    fun `rationale makes the first refusal retryable and Settings appears after Android suppresses the prompt`() {
        val permission = Manifest.permission.READ_PHONE_STATE
        val h = harness(granted = setOf(Manifest.permission.ACCESS_FINE_LOCATION))
        h.owner.onPermissionsResult(mapOf(permission to false)) { it == permission }
        assertEquals(DetectionPermissionPlanner.Action.SHOW_RATIONALE, h.flow.value.permissionAction)
        assertEquals(listOf(permission), h.flow.value.permissionRequest)

        h.owner.onPermissionsResult(mapOf(permission to false)) { false }
        assertEquals(DetectionPermissionPlanner.Action.OPEN_SETTINGS, h.flow.value.permissionAction)
        assertTrue(
            h.flow.value.permissionRequest
                .isEmpty(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun `a blocked permission does not prevent requesting other missing grants`() {
        val blocked = Manifest.permission.ACCESS_FINE_LOCATION
        val h = harness(denied = setOf(blocked))
        h.owner.refreshPermissionState { false }

        assertEquals(listOf(Manifest.permission.READ_PHONE_STATE), h.flow.value.permissionRequest)
        assertTrue(h.flow.value.canOpenPermissionSettings)
    }

    @Test
    @Config(sdk = [33])
    fun `refresh reads a grant made in Settings and the permission is requestable if it is later revoked`() {
        val permission = Manifest.permission.READ_PHONE_STATE
        val otherGrants = setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val h = harness(denied = setOf(permission), granted = otherGrants)
        h.owner.refreshPermissionState { false }
        assertEquals(DetectionPermissionPlanner.Action.OPEN_SETTINGS, h.flow.value.permissionAction)

        h.checker.granted = otherGrants + permission
        h.owner.refreshPermissionState { false }
        assertEquals(DetectionPermissionPlanner.Action.NONE, h.flow.value.permissionAction)

        h.checker.granted = otherGrants
        h.owner.refreshPermissionState { false }
        assertEquals(DetectionPermissionPlanner.Action.REQUEST, h.flow.value.permissionAction)
        assertEquals(listOf(permission), h.flow.value.permissionRequest)
    }

    @Test
    @Config(sdk = [33])
    fun `first check with missing permissions opens the rationale instead of running`() {
        val h = harness()
        h.owner.refreshPermissionState { false }
        assertFalse(h.flow.value.showOnboarding)

        assertTrue(h.owner.showRationaleBeforeCheck())
        assertTrue(h.flow.value.showOnboarding)
    }

    @Test
    @Config(sdk = [33])
    fun `check runs without the rationale once it was shown or nothing is missing`() {
        val seen = harness(onboardingShown = true)
        seen.owner.refreshPermissionState { false }
        assertFalse(seen.owner.showRationaleBeforeCheck())
        assertFalse(seen.flow.value.showOnboarding)

        val granted = harness(granted = DetectionPermissionStateOwner.requiredPermissions().toSet())
        granted.owner.refreshPermissionState { false }
        assertFalse(granted.owner.showRationaleBeforeCheck())
        assertFalse(granted.flow.value.showOnboarding)
    }

    private fun commonManifestFile(): File =
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { file -> file.exists() }
}
