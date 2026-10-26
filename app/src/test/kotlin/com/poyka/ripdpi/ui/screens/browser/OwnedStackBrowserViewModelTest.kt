package com.poyka.ripdpi.ui.screens.browser

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.OwnedStackBrowserBackend
import com.poyka.ripdpi.services.OwnedStackBrowserPage
import com.poyka.ripdpi.services.OwnedStackBrowserService
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.GeneralSecurityException
import kotlin.coroutines.ContinuationInterceptor

class OwnedStackBrowserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `non-https input shows the localized failure instead of crashing`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver, TestDispatchers)

        viewModel.load("http://example.com")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(FallbackMessage, state.errorMessage)
    }

    @Test
    fun `fetch failure shows the localized failure, not the exception text`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver, TestDispatchers)

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
            val viewModel = OwnedStackBrowserViewModel(service, FallbackStringResolver, TestDispatchers)

            viewModel.load("https://example.com")

            assertEquals(FallbackMessage, viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun `classified fetch failures show their localized class and others the browser fallback`() {
        val timeout =
            OwnedStackBrowserViewModel(
                RejectingBrowserService(SocketTimeoutException()),
                ResIdResolver,
                TestDispatchers,
            )
        timeout.load("https://example.com")
        assertEquals(R.string.error_user_timeout.toString(), timeout.uiState.value.errorMessage)

        val generic = OwnedStackBrowserViewModel(RejectingBrowserService(), ResIdResolver, TestDispatchers)
        generic.load("https://example.com")
        assertEquals(R.string.owned_stack_browser_request_failed.toString(), generic.uiState.value.errorMessage)
    }

    @Test
    fun `malformed input shows the localized failure instead of crashing`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver, TestDispatchers)

        viewModel.initialize("https://foo bar")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(FallbackMessage, state.errorMessage)
    }

    @Test
    fun `fetch and body conversion run on the default dispatcher and the body is capped`() =
        runTest {
            val background = StandardTestDispatcher(testScheduler)
            val service = RecordingBrowserService(body = "x".repeat(MaxDisplayedBodyChars + 10))
            val viewModel =
                OwnedStackBrowserViewModel(
                    service,
                    FallbackStringResolver,
                    AppCoroutineDispatchers(default = background, io = background, main = Dispatchers.Main),
                )

            viewModel.load("https://example.com")
            testScheduler.advanceUntilIdle()

            assertSame(background, service.fetchDispatcher)
            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
            assertEquals(MaxDisplayedBodyChars, state.page?.bodyText?.length)
        }

    private class RecordingBrowserService(
        private val body: String,
    ) : OwnedStackBrowserService {
        var fetchDispatcher: ContinuationInterceptor? = null

        override fun currentSupport(): OwnedStackBrowserSupport =
            OwnedStackBrowserSupport(platformHttpEngineAvailable = false, android17EchEligible = false)

        override fun normalizeUrl(rawUrl: String): String = rawUrl.trim()

        override suspend fun fetch(rawUrl: String): OwnedStackBrowserPage {
            fetchDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            return OwnedStackBrowserPage(
                requestedUrl = rawUrl,
                finalUrl = rawUrl,
                statusCode = 200,
                bodyText = body,
                contentType = null,
                backend = OwnedStackBrowserBackend.entries.first(),
                android17EchEligible = false,
            )
        }
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
        val TestDispatchers = AppCoroutineDispatchers(Dispatchers.Main, Dispatchers.Main, Dispatchers.Main)

        const val HttpsOnlyMessage = "Only HTTPS URLs are supported in the RIPDPI browser."
        const val FallbackMessage = "Owned-stack request failed."
    }
}
