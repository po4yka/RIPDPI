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

        val result = viewModel.uiState.value.scanResult
        assertTrue(result is ImportPayload.Profile)
        assertTrue((result as ImportPayload.Profile).request.profile is ProxyProfile.Vless)
        assertNull(viewModel.uiState.value.decodeError)
    }

    @Test
    fun `decoding an invalid payload surfaces a redacted error and no request`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)

        viewModel.onQrDecoded("http://malicious.example.com/long/redacted/path")

        // The error preview is capped at the first 16 characters of the payload.
        assertEquals("http://malicious", viewModel.uiState.value.decodeError)
        assertNull(viewModel.uiState.value.scanResult)
    }

    @Test
    fun `image fallback decode reuses the same dispatch path`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = false)

        viewModel.onQrDecoded("trojan://secret@example.com:443#FromImage")

        assertTrue(viewModel.uiState.value.scanResult is ImportPayload.Profile)
    }

    @Test
    fun `consuming the scan result clears it`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)
        viewModel.onQrDecoded("trojan://secret@example.com:443#QR")

        viewModel.consumeScanResult()

        assertNull(viewModel.uiState.value.scanResult)
    }

    @Test
    fun `repeated frames of the same code import it once`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)
        viewModel.onQrDecoded("trojan://secret@example.com:443#QR")
        viewModel.consumeScanResult()

        viewModel.onQrDecoded("trojan://secret@example.com:443#QR")

        assertNull(viewModel.uiState.value.scanResult)
    }

    @Test
    fun `an amneziavpn qr series shows progress and then imports the conf`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)
        val conf = "[Interface]\nPrivateKey = k==\n\n[Peer]\nPublicKey = p=="
        val compressed = AmneziaVpnKeyFixtures.qCompress(AmneziaVpnKeyFixtures.keyJson(conf).toByteArray())
        val half = compressed.size / 2

        viewModel.onQrDecoded(AmneziaVpnKeyFixtures.qrChunk(2, 0, compressed.copyOfRange(0, half)))
        val progress = viewModel.uiState.value.seriesProgress
        viewModel.onQrDecoded(AmneziaVpnKeyFixtures.qrChunk(2, 1, compressed.copyOfRange(half, compressed.size)))

        assertEquals(2, progress?.total)
        assertEquals(1, progress?.received)
        assertNull(viewModel.uiState.value.seriesProgress)
        assertEquals(ImportPayload.WireGuardConf(conf), viewModel.uiState.value.scanResult)
    }

    @Test
    fun `a failed series keeps its error while the camera still sees the last code`() {
        val viewModel = QrScannerViewModel()
        viewModel.onCameraPermissionResult(granted = true)
        val openVpn = AmneziaVpnKeyFixtures.keyJson("client\ndev tun", "amnezia-openvpn", "openvpn")
        val chunk = AmneziaVpnKeyFixtures.qrChunk(1, 0, AmneziaVpnKeyFixtures.qCompress(openVpn.toByteArray()))

        viewModel.onQrDecoded(chunk)
        viewModel.onQrDecoded(chunk)

        assertEquals("vpn://", viewModel.uiState.value.decodeError)
        assertNull(viewModel.uiState.value.seriesProgress)
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
