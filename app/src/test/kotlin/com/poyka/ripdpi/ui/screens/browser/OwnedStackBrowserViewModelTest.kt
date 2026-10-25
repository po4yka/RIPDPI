package com.poyka.ripdpi.ui.screens.browser

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.OwnedStackBrowserPage
import com.poyka.ripdpi.services.OwnedStackBrowserService
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.GeneralSecurityException

class OwnedStackBrowserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `non-https input shows the localized failure instead of crashing`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver)

        viewModel.load("http://example.com")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(FallbackMessage, state.errorMessage)
    }

    @Test
    fun `fetch failure shows the localized failure, not the exception text`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver)

        viewModel.load("https://example.com")

        assertEquals(FallbackMessage, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `decode and security failures from fetch show the localized failure`() {
        listOf(
            IllegalArgumentException("unexpected response JSON"),
            SecurityException("permission denied"),
            GeneralSecurityException("bad certificate"),
        ).forEach { failure ->
            val service = RejectingBrowserService(fetchFailure = failure)
            val viewModel = OwnedStackBrowserViewModel(service, FallbackStringResolver)

            viewModel.load("https://example.com")

            assertEquals(FallbackMessage, viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun `classified fetch failures show their localized class and others the browser fallback`() {
        val timeout = OwnedStackBrowserViewModel(RejectingBrowserService(SocketTimeoutException()), ResIdResolver)
        timeout.load("https://example.com")
        assertEquals(R.string.error_user_timeout.toString(), timeout.uiState.value.errorMessage)

        val generic = OwnedStackBrowserViewModel(RejectingBrowserService(), ResIdResolver)
        generic.load("https://example.com")
        assertEquals(R.string.owned_stack_browser_request_failed.toString(), generic.uiState.value.errorMessage)
    }

    @Test
    fun `malformed input shows the localized failure instead of crashing`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver)

        viewModel.initialize("https://foo bar")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(FallbackMessage, state.errorMessage)
    }

    /**
     * Throws the exception types that the production validator throws; OwnedStackBrowserServiceTest in
     * core/service pins those types against normalizeOwnedStackBrowserUrl.
     */
    private class RejectingBrowserService(
        private val fetchFailure: Exception = IOException("TLS handshake failed: raw engine text"),
    ) : OwnedStackBrowserService {
        override fun currentSupport(): OwnedStackBrowserSupport =
            OwnedStackBrowserSupport(platformHttpEngineAvailable = false, android17EchEligible = false)

        override fun normalizeUrl(rawUrl: String): String {
            val parsed = URI(rawUrl.trim())
            require(parsed.scheme.equals("https", ignoreCase = true)) { HttpsOnlyMessage }
            return parsed.toString()
        }

        override suspend fun fetch(rawUrl: String): OwnedStackBrowserPage {
            normalizeUrl(rawUrl)
            throw fetchFailure
        }
    }

    private object FallbackStringResolver : StringResolver {
        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = FallbackMessage

        override fun getQuantityString(
            resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String = FallbackMessage
    }

    private object ResIdResolver : StringResolver {
        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = resId.toString()

        override fun getQuantityString(
            resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String = resId.toString()
    }

    private companion object {
        const val HttpsOnlyMessage = "Only HTTPS URLs are supported in the RIPDPI browser."
        const val FallbackMessage = "Owned-stack request failed."
    }
}
