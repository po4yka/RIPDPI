package com.poyka.ripdpi.ui.screens.scanner

import android.Manifest
import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for UIX-1786264762917972: the scanner route's camera
 * permission synchronization must re-run when the runtime grant state changes while
 * the composition stays alive (e.g. the user grants the permission from
 * system settings and returns without the activity being recreated).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QrScannerRoutePermissionRecoveryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContentRecording(
        context: android.content.Context,
        effects: MutableList<String>,
    ): Lifecycle {
        var hostLifecycle: Lifecycle? = null
        composeRule.setContent {
            hostLifecycle = LocalLifecycleOwner.current.lifecycle
            val granted = rememberCameraPermissionGranted(context)
            LaunchedEffect(granted) {
                effects += if (granted) "granted" else "denied"
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(hostLifecycle)
    }

    @Test
    fun `grant while composed rekeys permission state effect`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
        val effects = mutableListOf<String>()

        val registry = setContentRecording(context, effects) as LifecycleRegistry

        assertEquals(listOf("denied"), effects)

        Shadows.shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        composeRule.runOnUiThread {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertEquals(listOf("denied", "granted"), effects)
    }

    @Test
    fun `revoke while composed rekeys permission state effect`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.CAMERA)
        val effects = mutableListOf<String>()

        val registry = setContentRecording(context, effects) as LifecycleRegistry

        assertEquals(listOf("granted"), effects)

        Shadows.shadowOf(context).denyPermissions(Manifest.permission.CAMERA)
        composeRule.runOnUiThread {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertEquals(listOf("granted", "denied"), effects)
    }

    @Test
    fun `revoked permission clears scanner state without requesting again`() {
        val viewModel = QrScannerViewModel()

        viewModel.syncCameraPermission(cameraPermissionGranted = true)
        assertEquals(ScannerCameraState.SCANNING, viewModel.uiState.value.cameraState)

        viewModel.syncCameraPermission(cameraPermissionGranted = false)

        assertEquals(ScannerCameraState.PERMISSION_DENIED, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `camera permission is requested once per scanner session`() {
        val viewModel = QrScannerViewModel()
        var permissionRequests = 0

        viewModel.requestCameraPermissionOnce(
            sessionId = "session-1",
            cameraPermissionGranted = true,
            requestCameraPermission = { permissionRequests += 1 },
        )
        viewModel.requestCameraPermissionOnce(
            sessionId = "session-1",
            cameraPermissionGranted = false,
            requestCameraPermission = { permissionRequests += 1 },
        )
        repeat(2) {
            viewModel.requestCameraPermissionOnce(
                sessionId = "session-2",
                cameraPermissionGranted = false,
                requestCameraPermission = { permissionRequests += 1 },
            )
        }
        viewModel.requestCameraPermissionOnce(
            sessionId = "session-3",
            cameraPermissionGranted = false,
            requestCameraPermission = { permissionRequests += 1 },
        )

        assertEquals(2, permissionRequests)
    }
}
