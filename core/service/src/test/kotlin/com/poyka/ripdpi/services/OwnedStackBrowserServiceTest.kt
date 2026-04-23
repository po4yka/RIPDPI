package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import com.poyka.ripdpi.core.NativeOwnedTlsHttpResult
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Provider

class OwnedStackBrowserServiceTest {
    @Test
    fun `service uses platform executor when HttpEngine path is available`() =
        kotlinx.coroutines.test.runTest {
            val platformExecutor =
                FakeOwnedStackPlatformBrowserExecutor(
                    response =
                        OwnedStackPlatformResponse(
                            finalUrl = "https://example.org/final",
                            statusCode = 200,
                            body = "<html><body>Hello</body></html>".toByteArray(),
                            contentType = "text/html; charset=UTF-8",
                        ),
                )
            val service =
                DefaultOwnedStackBrowserService(
                    supportProvider =
                        FixedOwnedStackBrowserSupportProvider(
                            OwnedStackBrowserSupport(
                                platformHttpEngineAvailable = true,
                                android17EchEligible = true,
                            ),
                        ),
                    platformExecutorProvider = providerOf(platformExecutor),
                    nativeOwnedTlsHttpFetcher = FakeNativeOwnedTlsHttpFetcher(),
                    tlsClientFactory = FakeOwnedTlsClientFactory(),
                )

            val page = service.fetch("example.org/final")

            assertEquals(OwnedStackBrowserBackend.HTTP_ENGINE, page.backend)
            assertTrue(page.android17EchEligible)
            assertEquals("https://example.org/final", page.requestedUrl)
            assertEquals("https://example.org/final", platformExecutor.seenUrls.single())
            assertEquals("<html><body>Hello</body></html>", page.bodyText)
        }

    @Test
    fun `service falls back to native owned tls when platform stack is unavailable`() =
        kotlinx.coroutines.test.runTest {
            val nativeFetcher =
                FakeNativeOwnedTlsHttpFetcher(
                    result =
                        NativeOwnedTlsHttpResult(
                            statusCode = 200,
                            body = "fallback ok".toByteArray(),
                            finalUrl = "https://blocked.example/",
                        ),
                )
            val service =
                DefaultOwnedStackBrowserService(
                    supportProvider =
                        FixedOwnedStackBrowserSupportProvider(
                            OwnedStackBrowserSupport(
                                platformHttpEngineAvailable = false,
                                android17EchEligible = false,
                            ),
                        ),
                    platformExecutorProvider = providerOf(FakeOwnedStackPlatformBrowserExecutor()),
                    nativeOwnedTlsHttpFetcher = nativeFetcher,
                    tlsClientFactory = FakeOwnedTlsClientFactory(),
                )

            val page = service.fetch("blocked.example")

            assertEquals(OwnedStackBrowserBackend.NATIVE_OWNED_TLS, page.backend)
            assertFalse(page.android17EchEligible)
            assertEquals("https://blocked.example", nativeFetcher.requests.single().url)
            assertEquals("firefox_ech_stable", page.tlsProfileId)
            assertEquals("fallback ok", page.bodyText)
        }

    @Test
    fun `launch helper normalizes authority to https url`() {
        assertEquals("https://example.org:443/", ownedStackBrowserLaunchUrl("example.org:443"))
    }

    @Test
    fun `normalizeUrl rejects non https schemes`() {
        val service =
            DefaultOwnedStackBrowserService(
                supportProvider = FixedOwnedStackBrowserSupportProvider(OwnedStackBrowserSupport(false, false)),
                platformExecutorProvider = providerOf(FakeOwnedStackPlatformBrowserExecutor()),
                nativeOwnedTlsHttpFetcher = FakeNativeOwnedTlsHttpFetcher(),
                tlsClientFactory = FakeOwnedTlsClientFactory(),
            )

        val error =
            runCatching { service.normalizeUrl("http://example.org") }
                .exceptionOrNull()

        requireNotNull(error)
        assertTrue(error.message?.contains("HTTPS") == true)
    }
}

private class FixedOwnedStackBrowserSupportProvider(
    private val support: OwnedStackBrowserSupport,
) : OwnedStackBrowserSupportProvider {
    override fun current(): OwnedStackBrowserSupport = support
}

private class FakeOwnedStackPlatformBrowserExecutor(
    private val response: OwnedStackPlatformResponse =
        OwnedStackPlatformResponse(
            finalUrl = "https://example.org/",
            statusCode = 200,
            body = ByteArray(0),
            contentType = "text/plain",
        ),
) : OwnedStackPlatformBrowserExecutor {
    val seenUrls = mutableListOf<String>()

    override suspend fun execute(url: String): OwnedStackPlatformResponse {
        seenUrls += url
        return response
    }
}

private class FakeNativeOwnedTlsHttpFetcher(
    private val result: NativeOwnedTlsHttpResult =
        NativeOwnedTlsHttpResult(
            statusCode = 200,
            body = ByteArray(0),
            finalUrl = "https://example.org/",
        ),
) : NativeOwnedTlsHttpFetcher {
    val requests = mutableListOf<NativeOwnedTlsHttpRequest>()

    override suspend fun execute(request: NativeOwnedTlsHttpRequest): NativeOwnedTlsHttpResult {
        requests += request
        return result
    }
}

private class FakeOwnedTlsClientFactory(
    private val selection: OwnedTlsFingerprintSelection =
        OwnedTlsFingerprintSelection(
            profileId = "firefox_ech_stable",
            tlsTemplateEchCapable = true,
        ),
) : OwnedTlsClientFactory {
    override fun currentProfile(): String = selection.profileId

    override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection = selection

    override fun create(
        forcedTlsVersions: List<okhttp3.TlsVersion>?,
        configure: OkHttpClient.Builder.() -> Unit,
    ): OkHttpClient = OkHttpClient.Builder().apply(configure).build()

    override fun createForAuthority(
        authority: String?,
        forcedTlsVersions: List<okhttp3.TlsVersion>?,
        configure: OkHttpClient.Builder.() -> Unit,
    ): OkHttpClient = OkHttpClient.Builder().apply(configure).build()
}

private fun <T> providerOf(value: T): Provider<T> = Provider { value }
