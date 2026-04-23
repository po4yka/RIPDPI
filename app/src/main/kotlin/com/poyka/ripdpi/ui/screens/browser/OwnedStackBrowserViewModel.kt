package com.poyka.ripdpi.ui.screens.browser

import androidx.core.text.HtmlCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.services.OwnedStackBrowserBackend
import com.poyka.ripdpi.services.OwnedStackBrowserPage
import com.poyka.ripdpi.services.OwnedStackBrowserService
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OwnedStackBrowserViewModel
    @Inject
    constructor(
        private val browserService: OwnedStackBrowserService,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                OwnedStackBrowserUiState(
                    support = browserService.currentSupport(),
                ),
            )
        val uiState: StateFlow<OwnedStackBrowserUiState> = _uiState.asStateFlow()

        private var initialized = false

        fun initialize(initialUrl: String) {
            if (initialized) {
                return
            }
            initialized = true
            if (initialUrl.isBlank()) {
                return
            }
            updateUrl(initialUrl)
            load(initialUrl)
        }

        fun updateUrl(value: String) {
            _uiState.update { state -> state.copy(inputUrl = value) }
        }

        fun reload() {
            load(_uiState.value.inputUrl)
        }

        fun load(rawUrl: String) {
            viewModelScope.launch {
                _uiState.update { state ->
                    state.copy(
                        isLoading = true,
                        errorMessage = null,
                    )
                }
                try {
                    val page = browserService.fetch(rawUrl)
                    _uiState.update { state ->
                        state.copy(
                            inputUrl = page.finalUrl,
                            page = page.toUiModel(),
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Owned-stack request failed.",
                        )
                    }
                }
            }
        }
    }

data class OwnedStackBrowserUiState(
    val support: OwnedStackBrowserSupport,
    val inputUrl: String = "",
    val isLoading: Boolean = false,
    val page: OwnedStackBrowserPageUiModel? = null,
    val errorMessage: String? = null,
)

data class OwnedStackBrowserPageUiModel(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val bodyText: String,
    val backend: OwnedStackBrowserBackend,
    val android17EchEligible: Boolean,
    val tlsProfileId: String? = null,
)

private fun OwnedStackBrowserPage.toUiModel(): OwnedStackBrowserPageUiModel =
    OwnedStackBrowserPageUiModel(
        requestedUrl = requestedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        bodyText =
            if (contentType?.contains("html", ignoreCase = true) == true) {
                HtmlCompat
                    .fromHtml(bodyText, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    .toString()
            } else {
                bodyText
            },
        backend = backend,
        android17EchEligible = android17EchEligible,
        tlsProfileId = tlsProfileId,
    )
