package com.poyka.ripdpi.ui.screens.scanner

import android.Manifest
import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.R
import com.poyka.ripdpi.ui.theme.RipDpiTheme
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

        viewModel.syncCameraPermission(cameraPermissionGranted = true, canRequestAgain = false)
        assertEquals(ScannerCameraState.SCANNING, viewModel.uiState.value.cameraState)

        viewModel.syncCameraPermission(cameraPermissionGranted = false, canRequestAgain = false)

        assertEquals(ScannerCameraState.PERMISSION_DENIED, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `missing camera permission shows the rationale once per scanner session`() {
        val viewModel = QrScannerViewModel()

        viewModel.showCameraRationaleOnce(sessionId = "session-1", cameraPermissionGranted = false)
        assertEquals(ScannerCameraState.REQUESTING_PERMISSION, viewModel.uiState.value.cameraState)
        viewModel.onCameraPermissionResult(granted = false, canRequestAgain = false)
        viewModel.showCameraRationaleOnce(sessionId = "session-1", cameraPermissionGranted = false)
        assertEquals(ScannerCameraState.PERMISSION_DENIED, viewModel.uiState.value.cameraState)

        viewModel.showCameraRationaleOnce(sessionId = "session-2", cameraPermissionGranted = false)
        assertEquals(ScannerCameraState.REQUESTING_PERMISSION, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `system camera prompt opens only from the rationale continue action`() {
        var permissionRequests = 0
        composeRule.setContent {
            RipDpiTheme {
                QrScannerScreen(
                    uiState = QrScannerUiState(cameraState = ScannerCameraState.REQUESTING_PERMISSION),
                    onBack = {},
                    onQrDecoded = {},
                    onDismissError = {},
                    onPickImage = {},
                    onRequestCameraPermission = { permissionRequests += 1 },
                    onOpenAppSettings = {},
                )
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        composeRule.onNodeWithText(app.getString(R.string.scanner_permission_rationale_body)).assertExists()
        assertEquals(0, permissionRequests)

        composeRule.onNodeWithText(app.getString(R.string.scanner_permission_continue)).performClick()

        assertEquals(1, permissionRequests)
    }

    @Test
    fun `recreation while the rationale shows keeps the rationale`() {
        val viewModel = QrScannerViewModel()
        lateinit var composed: MutableState<Boolean>

        composeRule.setContent {
            composed = remember { mutableStateOf(true) }
            if (composed.value) {
                QrScannerPermissionEffects(
                    sessionId = "session-1",
                    cameraPermissionGranted = false,
                    canRequestCameraPermissionAgain = { false },
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(ScannerCameraState.REQUESTING_PERMISSION, viewModel.uiState.value.cameraState)

        // The view model outlives the composition, as it does across a rotation or a back-stack return.
        composeRule.runOnUiThread { composed.value = false }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composed.value = true }
        composeRule.waitForIdle()

        assertEquals(ScannerCameraState.REQUESTING_PERMISSION, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `grant state changes in the same session only sync the scanner state`() {
        val viewModel = QrScannerViewModel()
        lateinit var permissionGranted: MutableState<Boolean>

        composeRule.setContent {
            permissionGranted = remember { mutableStateOf(false) }
            QrScannerPermissionEffects(
                sessionId = "session-1",
                cameraPermissionGranted = permissionGranted.value,
                canRequestCameraPermissionAgain = { false },
                viewModel = viewModel,
            )
        }
        composeRule.waitForIdle()

        assertEquals(ScannerCameraState.REQUESTING_PERMISSION, viewModel.uiState.value.cameraState)

        composeRule.runOnUiThread { permissionGranted.value = true }
        composeRule.waitForIdle()

        assertEquals(ScannerCameraState.SCANNING, viewModel.uiState.value.cameraState)

        composeRule.runOnUiThread { permissionGranted.value = false }
        composeRule.waitForIdle()

        assertEquals(ScannerCameraState.PERMISSION_DENIED, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `a first denial or an expired one-time grant can still request the camera`() {
        val deniedOnce = QrScannerViewModel()
        deniedOnce.showCameraRationaleOnce(sessionId = "session-1", cameraPermissionGranted = false)
        deniedOnce.onCameraPermissionResult(granted = false, canRequestAgain = true)
        assertEquals(ScannerCameraState.PERMISSION_DENIED, deniedOnce.uiState.value.cameraState)
        assertEquals(true, deniedOnce.uiState.value.canRequestCameraPermissionAgain)

        val oneTimeGrant = QrScannerViewModel()
        oneTimeGrant.syncCameraPermission(cameraPermissionGranted = true, canRequestAgain = false)
        oneTimeGrant.syncCameraPermission(cameraPermissionGranted = false, canRequestAgain = true)
        assertEquals(ScannerCameraState.PERMISSION_DENIED, oneTimeGrant.uiState.value.cameraState)
        assertEquals(true, oneTimeGrant.uiState.value.canRequestCameraPermissionAgain)

        val deniedTwice = QrScannerViewModel()
        deniedTwice.onCameraPermissionResult(granted = false, canRequestAgain = false)
        assertEquals(false, deniedTwice.uiState.value.canRequestCameraPermissionAgain)
    }

    @Test
    fun `denied state asks again while the system still prompts`() {
        var permissionRequests = 0
        var settingsOpens = 0
        composeRule.setContent {
            RipDpiTheme {
                QrScannerScreen(
                    uiState =
                        QrScannerUiState(
                            cameraState = ScannerCameraState.PERMISSION_DENIED,
                            canRequestCameraPermissionAgain = true,
                        ),
                    onBack = {},
                    onQrDecoded = {},
                    onDismissError = {},
                    onPickImage = {},
                    onRequestCameraPermission = { permissionRequests += 1 },
                    onOpenAppSettings = { settingsOpens += 1 },
                )
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        composeRule
            .onNodeWithText(app.getString(R.string.settings_permission_action_open_settings))
            .assertDoesNotExist()

        composeRule.onNodeWithText(app.getString(R.string.scanner_permission_continue)).performClick()

        assertEquals(1, permissionRequests)
        assertEquals(0, settingsOpens)
    }

    @Test
    fun `denied state opens settings once the system stops prompting`() {
        var permissionRequests = 0
        var settingsOpens = 0
        composeRule.setContent {
            RipDpiTheme {
                QrScannerScreen(
                    uiState =
                        QrScannerUiState(
                            cameraState = ScannerCameraState.PERMISSION_DENIED,
                            canRequestCameraPermissionAgain = false,
                        ),
                    onBack = {},
                    onQrDecoded = {},
                    onDismissError = {},
                    onPickImage = {},
                    onRequestCameraPermission = { permissionRequests += 1 },
                    onOpenAppSettings = { settingsOpens += 1 },
                )
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        composeRule.onNodeWithText(app.getString(R.string.scanner_permission_continue)).assertDoesNotExist()

        composeRule.onNodeWithText(app.getString(R.string.settings_permission_action_open_settings)).performClick()

        assertEquals(0, permissionRequests)
        assertEquals(1, settingsOpens)
    }
}
