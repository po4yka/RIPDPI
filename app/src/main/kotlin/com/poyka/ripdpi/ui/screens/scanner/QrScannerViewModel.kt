package com.poyka.ripdpi.ui.screens.scanner

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.proxyimport.AmneziaQrChunkAssembler
import com.poyka.ripdpi.proxyimport.AmneziaVpnKeyDecoder
import com.poyka.ripdpi.proxyimport.ImportPayload
import com.poyka.ripdpi.proxyimport.ScannerScanResult
import com.poyka.ripdpi.proxyimport.ScannerUriDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Camera-availability phase for the QR scanner screen. */
enum class ScannerCameraState {
    /** Awaiting the runtime camera-permission result. */
    REQUESTING_PERMISSION,

    /** Camera granted; the CameraX preview + ML Kit analyzer are live. */
    SCANNING,

    /** Camera denied; only the image-file fallback path is available. */
    PERMISSION_DENIED,
}

/** UI state for the QR scanner screen. */
data class QrScannerUiState(
    val cameraState: ScannerCameraState = ScannerCameraState.REQUESTING_PERMISSION,
    val imageFallbackAvailable: Boolean = true,
    /** A recognised payload awaiting navigation. */
    val scanResult: ImportPayload? = null,
    val decodeError: String? = null,
    /** Codes scanned so far from a multi-code AmneziaVPN key; `null` outside such a series. */
    val seriesProgress: AmneziaQrChunkAssembler.Progress? = null,
)

/**
 * Backing [ViewModel] for the QR scanner screen.
 *
 * Holds the camera-permission state machine and the decoded-text handling shared by the
 * live CameraX path and the image-file fallback. A denied camera permission does not
 * brick the flow — [QrScannerUiState.imageFallbackAvailable] stays `true` so the user can
 * still pick a QR image from storage. Decoded text is classified by the shared
 * [ScannerUriDispatcher]; an invalid payload surfaces a redacted (first 16 chars) error
 * and the full QR content is never logged.
 */
@HiltViewModel
class QrScannerViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(QrScannerUiState())
        val uiState: StateFlow<QrScannerUiState> = _uiState.asStateFlow()
        private var cameraPermissionRequestSessionId: String? = null
        private var lastImportedPayloadHash: Int? = null
        private val seriesAssembler = AmneziaQrChunkAssembler()

        /** Requests camera permission at most once for each navigation session. */
        internal fun requestCameraPermissionOnce(
            sessionId: String,
            cameraPermissionGranted: Boolean,
            requestCameraPermission: () -> Unit,
        ) {
            if (cameraPermissionRequestSessionId == sessionId) return
            cameraPermissionRequestSessionId = sessionId
            if (!cameraPermissionGranted) {
                _uiState.update {
                    it.copy(
                        cameraState = ScannerCameraState.REQUESTING_PERMISSION,
                        imageFallbackAvailable = true,
                    )
                }
                requestCameraPermission()
            }
        }

        /** Advances the camera state machine from the runtime-permission result. */
        fun onCameraPermissionResult(granted: Boolean) {
            _uiState.update {
                it.copy(
                    cameraState =
                        if (granted) {
                            ScannerCameraState.SCANNING
                        } else {
                            ScannerCameraState.PERMISSION_DENIED
                        },
                    imageFallbackAvailable = true,
                )
            }
        }

        /**
         * Handles a QR payload decoded from either the camera analyzer or a picked image.
         * A recognised proxy URI, subscription link, or AmneziaWG `.conf` is exposed as
         * [QrScannerUiState.scanResult] for the screen to navigate on; otherwise a redacted
         * preview is surfaced as [QrScannerUiState.decodeError].
         */
        fun onQrDecoded(payload: String) {
            val result =
                when (val chunk = seriesAssembler.accept(payload)) {
                    is AmneziaQrChunkAssembler.Result.Partial -> {
                        _uiState.update { it.copy(seriesProgress = chunk.progress, decodeError = null) }
                        return
                    }

                    is AmneziaQrChunkAssembler.Result.Complete -> {
                        _uiState.update { it.copy(seriesProgress = null) }
                        AmneziaVpnKeyDecoder
                            .confFromCompressed(chunk.compressed)
                            ?.let { ScannerScanResult.Recognized(ImportPayload.WireGuardConf(it)) }
                            ?: ScannerScanResult.Invalid(AmneziaVpnKeyDecoder.Scheme)
                    }

                    null -> {
                        ScannerUriDispatcher.dispatch(payload)
                    }
                }
            when (result) {
                is ScannerScanResult.Invalid -> {
                    _uiState.update { it.copy(decodeError = result.redactedPreview, scanResult = null) }
                }

                is ScannerScanResult.Recognized -> {
                    // The analyzer reports every frame that holds the code; the navigation pops
                    // this screen, so one recognised code is imported once. Only a hash is kept.
                    if (payload.hashCode() == lastImportedPayloadHash) return
                    lastImportedPayloadHash = payload.hashCode()
                    _uiState.update { it.copy(scanResult = result.payload, decodeError = null) }
                }
            }
        }

        /** Clears the pending scan result once the screen has navigated on it. */
        fun consumeScanResult() {
            _uiState.update { it.copy(scanResult = null) }
        }

        /** Clears the decode error so the analyzer can resume scanning. */
        fun consumeDecodeError() {
            _uiState.update { it.copy(decodeError = null) }
        }
    }
