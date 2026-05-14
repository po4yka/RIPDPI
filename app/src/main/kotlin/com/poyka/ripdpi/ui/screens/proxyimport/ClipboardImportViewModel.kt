package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.proxyimport.ClipboardImportClassifier
import com.poyka.ripdpi.proxyimport.ClipboardImportResult
import com.poyka.ripdpi.proxyimport.ClipboardReader
import com.poyka.ripdpi.proxyimport.ProxyImportRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the explicit "Import from clipboard" action.
 *
 * Exactly one of [navigateToConfirm] / [unknownContentScheme] / [clipboardEmpty] is
 * populated after an [ClipboardImportViewModel.onImportFromClipboard] call. None of these
 * fields ever carries the raw clipboard payload — at most the scheme prefix.
 */
data class ClipboardImportUiState(
    val navigateToConfirm: ProxyImportRequest.Profile? = null,
    val unknownContentScheme: String? = null,
    val clipboardEmpty: Boolean = false,
    val clearClipboardAfterImport: Boolean = false,
)

/**
 * Backing [ViewModel] for the explicit clipboard-import menu action.
 *
 * The clipboard is read **once, only** when [onImportFromClipboard] is called from the
 * user's menu tap. There is no watcher, no `OnPrimaryClipChangedListener`, and nothing
 * polls in the background — constructing this ViewModel does not touch the clipboard.
 *
 * Parsing reuses the shared [ClipboardImportClassifier]; a recognised proxy URI yields a
 * [ProxyImportRequest.Profile] for the screen to route on, unknown content yields a typed
 * error carrying only the scheme. The "clear clipboard after import" step is opt-in and
 * defaults to off; when enabled it runs only after a *successful* import, to reduce how
 * long a credential-bearing URI lingers in the system clipboard.
 */
@HiltViewModel
class ClipboardImportViewModel
    @Inject
    constructor(
        private val clipboardReader: ClipboardReader,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ClipboardImportUiState())
        val uiState: StateFlow<ClipboardImportUiState> = _uiState.asStateFlow()

        /**
         * Performs the single, explicit clipboard read and classifies it. Call this only
         * from the user's "Import from clipboard" menu tap.
         */
        fun onImportFromClipboard() {
            val text = clipboardReader.readPrimaryClipText()
            when (val result = ClipboardImportClassifier.classify(text)) {
                is ClipboardImportResult.Importable -> {
                    if (_uiState.value.clearClipboardAfterImport) {
                        clipboardReader.clearPrimaryClip()
                    }
                    _uiState.update {
                        it.copy(
                            navigateToConfirm = result.request,
                            unknownContentScheme = null,
                            clipboardEmpty = false,
                        )
                    }
                }

                is ClipboardImportResult.UnknownContent -> {
                    _uiState.update {
                        it.copy(
                            navigateToConfirm = null,
                            unknownContentScheme = result.scheme,
                            clipboardEmpty = false,
                        )
                    }
                }

                ClipboardImportResult.Empty -> {
                    _uiState.update {
                        it.copy(
                            navigateToConfirm = null,
                            unknownContentScheme = null,
                            clipboardEmpty = true,
                        )
                    }
                }
            }
        }

        /** Opts in to / out of clearing the clipboard after a successful import. */
        fun setClearClipboardAfterImport(enabled: Boolean) {
            _uiState.update { it.copy(clearClipboardAfterImport = enabled) }
        }

        /** Clears the pending navigation request once the screen has routed on it. */
        fun consumeNavigation() {
            _uiState.update { it.copy(navigateToConfirm = null) }
        }

        /** Dismisses the error banner (unknown content / empty clipboard). */
        fun dismissError() {
            _uiState.update { it.copy(unknownContentScheme = null, clipboardEmpty = false) }
        }
    }
