package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the "Share profile" flow.
 *
 * The flow is a small state machine: request -> warning (5s hold) -> acknowledged ->
 * share content revealed. [shareUri] stays `null` until the user has acknowledged the
 * secrets-redaction warning, so the credential-bearing URI is never shown by accident.
 */
data class ProfileShareUiState(
    val profile: ProxyProfile? = null,
    val warningVisible: Boolean = false,
    val warningAcknowledgeEnabled: Boolean = false,
    val shareUri: String? = null,
    val unshareable: Boolean = false,
)

/**
 * Backing [ViewModel] for QR + plain-URI share of a saved profile.
 *
 * Generation is fully offline (no network round-trip) — the share URI is the canonical
 * per-protocol scheme produced by [ProxyUriCodec], and the QR matrix is encoded
 * locally by [com.poyka.ripdpi.proxyimport.QrCodeEncoder].
 *
 * The first thing every share session shows is a secrets-redaction warning: the QR and
 * URI embed credentials. The warning's acknowledge action is disabled for a 5-second hold
 * ([WARNING_HOLD_MILLIS]) and the warning cannot be permanently suppressed — every
 * [onShareProfileRequested] re-arms it from scratch.
 */
@HiltViewModel
class ProfileShareViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileShareUiState())
        val uiState: StateFlow<ProfileShareUiState> = _uiState.asStateFlow()

        /**
         * Starts a share session for [profile]. Always re-shows the secrets warning with
         * the acknowledge action disabled; the share content is not revealed yet.
         */
        fun onShareProfileRequested(profile: ProxyProfile) {
            _uiState.value =
                ProfileShareUiState(
                    profile = profile,
                    warningVisible = true,
                    warningAcknowledgeEnabled = false,
                    shareUri = null,
                    unshareable = false,
                )
        }

        /**
         * Marks the 5-second warning hold as elapsed, enabling the acknowledge action.
         * The screen drives this from a `LaunchedEffect` timer.
         */
        fun onWarningHoldElapsed() {
            _uiState.update {
                if (it.warningVisible) it.copy(warningAcknowledgeEnabled = true) else it
            }
        }

        /**
         * Acknowledges the secrets warning. A no-op while the 5-second hold is still
         * active. On acknowledgement the canonical share URI is revealed (or [ProfileShareUiState.unshareable]
         * is set when the profile cannot be expressed as a share URI).
         */
        fun onWarningAcknowledged() {
            val current = _uiState.value
            if (!current.warningVisible || !current.warningAcknowledgeEnabled) return
            val profile = current.profile ?: return
            val uri = ProxyUriCodec.encodeOrNull(profile)
            _uiState.value =
                current.copy(
                    warningVisible = false,
                    shareUri = uri,
                    unshareable = uri == null,
                )
        }

        /** Resets the session when the share sheet is dismissed. */
        fun onShareDismissed() {
            _uiState.value = ProfileShareUiState()
        }

        companion object {
            /**
             * How long the secrets-redaction warning's acknowledge action stays disabled.
             * The risk of leaking credentials by sharing is high enough that a forced
             * 5-second read is warranted.
             */
            const val WARNING_HOLD_MILLIS: Long = 5_000L
        }
    }
