package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.ui.screens.scanner.QrScannerViewModel
import com.poyka.ripdpi.ui.screens.scanner.ScannerCameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [QrScannerViewModel]: the camera-permission state machine and the
 * decoded-text handling shared by the live CameraX path and the image-file fallback.
 * A denied camera permission must not brick the flow — the image-pick fallback stays
 * available — and an invalid decode surfaces a redacted error.
 */
class QrScannerViewModelTest {
    @Test
    fun `initial state requests camera permission`() {
        val viewModel = QrScannerViewModel()

        assertEquals(ScannerCameraState.REQUESTING_PERMISSION, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `granting camera permission moves to scanning`() {
        val viewModel = QrScannerViewModel()

        viewModel.onCameraPermissionResult(granted = true)

        assertEquals(ScannerCameraState.SCANNING, viewModel.uiState.value.cameraState)
    }

    @Test
    fun `denying camera permission degrades to image-pick fallback, not a dead end`() {
        val viewModel = QrScannerViewModel()

        viewModel.onCameraPermissionResult(granted = false)

        assertEquals(ScannerCameraState.PERMISSION_DENIED, viewModel.uiState.value.cameraState)
        assertTrue(
            "Image-pick fallback must stay available after denial",
            viewModel.uiState.value.imageFallbackAvailable,
        )
    }

    @Test
    fun `decoding an allowlisted proxy uri emits a profile import request`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)

        viewModel.onQrDecoded("vless://11111111-2222-3333-4444-555555555555@example.com:443#QR")

        val request = viewModel.uiState.value.importRequest
        assertTrue(request is ProxyImportRequest.Profile)
        assertTrue((request as ProxyImportRequest.Profile).profile is ProxyProfile.Vless)
        assertNull(viewModel.uiState.value.decodeError)
    }

    @Test
    fun `decoding an invalid payload surfaces a redacted error and no request`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)

        viewModel.onQrDecoded("http://malicious.example.com/long/redacted/path")

        // The error preview is capped at the first 16 characters of the payload.
        assertEquals("http://malicious", viewModel.uiState.value.decodeError)
        assertNull(viewModel.uiState.value.importRequest)
    }

    @Test
    fun `image fallback decode reuses the same dispatch path`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = false)

        viewModel.onQrDecoded("trojan://secret@example.com:443#FromImage")

        assertTrue(viewModel.uiState.value.importRequest is ProxyImportRequest.Profile)
    }

    @Test
    fun `consuming the import request clears it`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)
        viewModel.onQrDecoded("trojan://secret@example.com:443#QR")

        viewModel.consumeImportRequest()

        assertNull(viewModel.uiState.value.importRequest)
    }

    @Test
    fun `consuming the decode error clears it so scanning can resume`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)
        viewModel.onQrDecoded("not-a-uri")

        viewModel.consumeDecodeError()

        assertNull(viewModel.uiState.value.decodeError)
    }
}
