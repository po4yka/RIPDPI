package com.poyka.ripdpi.ui.screens.browser

import androidx.core.text.HtmlCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.platform.userMessage
import com.poyka.ripdpi.services.OwnedStackBrowserBackend
import com.poyka.ripdpi.services.OwnedStackBrowserPage
import com.poyka.ripdpi.services.OwnedStackBrowserService
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import com.poyka.ripdpi.services.OwnedStackExecutionTrace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URISyntaxException
import java.security.GeneralSecurityException
import javax.inject.Inject

@HiltViewModel
class OwnedStackBrowserViewModel
    @Inject
    constructor(
        private val browserService: OwnedStackBrowserService,
        private val stringResolver: StringResolver,
        private val dispatchers: AppCoroutineDispatchers,
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
            // Validate first: the service rejects input with IllegalArgumentException (blank, non-HTTPS,
            // no host) or URISyntaxException (malformed), and its texts are not localized.
            val url =
                try {
                    browserService.normalizeUrl(rawUrl)
                } catch (_: IllegalArgumentException) {
                    showRequestFailed()
                    return
                } catch (_: URISyntaxException) {
                    showRequestFailed()
                    return
                }
            viewModelScope.launch {
                _uiState.update { state ->
                    state.copy(
                        isLoading = true,
                        errorMessage = null,
                    )
                }
                try {
                    // Body decoding and the HTML parse can take seconds for a large page; keep them off Main.
                    val page = withContext(dispatchers.default) { browserService.fetch(url).toUiModel() }
                    _uiState.update { state ->
                        state.copy(
                            inputUrl = page.finalUrl,
                            page = page,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    showRequestFailed(error)
                } catch (error: IllegalArgumentException) {
                    // Includes SerializationException from decoding the response.
                    showRequestFailed(error)
                } catch (error: SecurityException) {
                    showRequestFailed(error)
                } catch (error: GeneralSecurityException) {
                    showRequestFailed(error)
                }
            }
        }

        private fun showRequestFailed(error: Throwable? = null) {
            val message =
                error?.let { stringResolver.userMessage(it, R.string.owned_stack_browser_request_failed) }
                    ?: stringResolver.getString(R.string.owned_stack_browser_request_failed)
            _uiState.update { state -> state.copy(isLoading = false, errorMessage = message) }
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
    val executionTrace: OwnedStackExecutionTrace = OwnedStackExecutionTrace(),
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
            }.take(MaxDisplayedBodyChars),
        backend = backend,
        android17EchEligible = android17EchEligible,
        tlsProfileId = tlsProfileId,
        executionTrace = executionTrace,
    )

// The screen lays the body out as one Text on Main; a multi-megabyte body freezes it.
internal const val MaxDisplayedBodyChars = 256 * 1024
