package com.poyka.ripdpi.ui.screens.browser

import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.OwnedStackBrowserPage
import com.poyka.ripdpi.services.OwnedStackBrowserService
import com.poyka.ripdpi.services.OwnedStackBrowserSupport
import com.poyka.ripdpi.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.net.URI

class OwnedStackBrowserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `non-https input shows the validation message instead of crashing`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver)

        viewModel.load("http://example.com")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(HttpsOnlyMessage, state.errorMessage)
    }

    @Test
    fun `malformed input shows the generic failure instead of crashing`() {
        val viewModel = OwnedStackBrowserViewModel(RejectingBrowserService(), FallbackStringResolver)

        viewModel.initialize("https://foo bar")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(FallbackMessage, state.errorMessage)
    }

    /** Validates like the production service: [URI] parse first, then an HTTPS-only `require`. */
    private class RejectingBrowserService : OwnedStackBrowserService {
        override fun currentSupport(): OwnedStackBrowserSupport =
            OwnedStackBrowserSupport(platformHttpEngineAvailable = false, android17EchEligible = false)

        override fun normalizeUrl(rawUrl: String): String {
            val parsed = URI(rawUrl.trim())
            require(parsed.scheme.equals("https", ignoreCase = true)) { HttpsOnlyMessage }
            return parsed.toString()
        }

        override suspend fun fetch(rawUrl: String): OwnedStackBrowserPage {
            normalizeUrl(rawUrl)
            error("network fetch is out of scope for this test")
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

    private companion object {
        const val HttpsOnlyMessage = "Only HTTPS URLs are supported in the RIPDPI browser."
        const val FallbackMessage = "Owned-stack request failed."
    }
}
