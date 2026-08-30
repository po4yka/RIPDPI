package com.poyka.ripdpi.ui.screens.scanner

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
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
    val importRequest: ProxyImportRequest? = null,
    val decodeError: String? = null,
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

        /** Requests camera permission at most once for each navigation session. */
        internal fun requestCameraPermissionOnce(
            sessionId: String,
            cameraPermissionGranted: Boolean,
            requestCameraPermission: () -> Unit,
        ) {
            if (cameraPermissionRequestSessionId == sessionId) return
            cameraPermissionRequestSessionId = sessionId
            if (!cameraPermissionGranted) {
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
         * On a valid allowlisted proxy URI the resulting [ProxyImportRequest] is exposed
         * for the screen to navigate on; otherwise a redacted preview is surfaced as
         * [QrScannerUiState.decodeError].
         */
        fun onQrDecoded(payload: String) {
            when (val result = ScannerUriDispatcher.dispatch(payload)) {
                is ScannerScanResult.Profile -> {
                    _uiState.update { it.copy(importRequest = result.request, decodeError = null) }
                }

                is ScannerScanResult.Invalid -> {
                    _uiState.update { it.copy(decodeError = result.redactedPreview, importRequest = null) }
                }
            }
        }

        /** Clears the pending import request once the screen has navigated on it. */
        fun consumeImportRequest() {
            _uiState.update { it.copy(importRequest = null) }
        }

        /** Clears the decode error so the analyzer can resume scanning. */
        fun consumeDecodeError() {
            _uiState.update { it.copy(decodeError = null) }
        }
    }
