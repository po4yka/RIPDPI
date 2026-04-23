package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import com.poyka.ripdpi.core.NativeOwnedTlsHttpResult
import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.ServerCapabilityObservation
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import javax.inject.Provider

class OwnedStackBrowserServiceTest {
    @Test
    fun `secure client uses platform path when confirmed ECH-capable authority is cached`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore =
                TestServerCapabilityStore().also { store ->
                    store.rememberDirectPathObservation(
                        fingerprint = fingerprint,
                        authority = "example.org:443",
                        observation =
                            ServerCapabilityObservation(
                                dnsClassification = DirectDnsClassification.ECH_CAPABLE,
                            ),
                        recordedAt = System.currentTimeMillis(),
                    )
                }
            val platformExecutor =
                FakeOwnedStackPlatformBrowserExecutor {
                    OwnedStackPlatformResponse(
                        finalUrl = "https://example.org/final",
                        statusCode = 200,
                        body = "platform ok".toByteArray(),
                        contentType = "text/plain",
                    )
                }
            val client =
                DefaultSecureHttpClient(
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
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    serverCapabilityStore = capabilityStore,
                )

            val response =
                client.execute(
                    SecureHttpRequest(
                        url = "example.org/final",
                        echMode = SecureHttpEchMode.REQUIRE_CONFIRMED,
                    ),
                )

            assertEquals(OwnedStackBrowserBackend.HTTP_ENGINE, response.backend)
            assertEquals(SecureHttpEchMode.REQUIRE_CONFIRMED, response.executionTrace.effectiveEchMode)
            assertTrue(response.executionTrace.confirmedEchCapableAuthority)
            assertEquals(1, platformExecutor.requests.size)
            assertTrue(platformExecutor.requests.single().quicEnabled)
        }

    @Test
    fun `secure client retries with H2-only platform stack after QUIC-capable failure`() =
        runTest {
            val platformExecutor =
                FakeOwnedStackPlatformBrowserExecutor { request ->
                    if (request.quicEnabled) {
                        throw IllegalStateException("quic path failed")
                    }
                    OwnedStackPlatformResponse(
                        finalUrl = "https://example.org/h2",
                        statusCode = 200,
                        body = "h2 ok".toByteArray(),
                        contentType = "text/plain",
                    )
                }
            val client =
                DefaultSecureHttpClient(
                    supportProvider =
                        FixedOwnedStackBrowserSupportProvider(
                            OwnedStackBrowserSupport(
                                platformHttpEngineAvailable = true,
                                android17EchEligible = false,
                            ),
                        ),
                    platformExecutorProvider = providerOf(platformExecutor),
                    nativeOwnedTlsHttpFetcher = FakeNativeOwnedTlsHttpFetcher(),
                    tlsClientFactory = FakeOwnedTlsClientFactory(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    serverCapabilityStore = TestServerCapabilityStore(),
                )

            val response = client.execute(SecureHttpRequest(url = "example.org/h2"))

            assertEquals(OwnedStackBrowserBackend.HTTP_ENGINE, response.backend)
            assertTrue(response.executionTrace.platformAttempted)
            assertTrue(response.executionTrace.h2RetryTriggered)
            assertEquals(SecureHttpQuicPolicy.H2_ONLY, response.executionTrace.finalQuicPolicy)
            assertEquals(listOf(true, false), platformExecutor.requests.map(OwnedStackPlatformRequest::quicEnabled))
        }

    @Test
    fun `secure client falls back to native when confirmed ECH is required but not cached`() =
        runTest {
            val nativeFetcher =
                FakeNativeOwnedTlsHttpFetcher(
                    result =
                        NativeOwnedTlsHttpResult(
                            statusCode = 200,
                            body = "fallback ok".toByteArray(),
                            finalUrl = "https://blocked.example/",
                        ),
                )
            val client =
                DefaultSecureHttpClient(
                    supportProvider =
                        FixedOwnedStackBrowserSupportProvider(
                            OwnedStackBrowserSupport(
                                platformHttpEngineAvailable = true,
                                android17EchEligible = true,
                            ),
                        ),
                    platformExecutorProvider = providerOf(FakeOwnedStackPlatformBrowserExecutor()),
                    nativeOwnedTlsHttpFetcher = nativeFetcher,
                    tlsClientFactory = FakeOwnedTlsClientFactory(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    serverCapabilityStore = TestServerCapabilityStore(),
                )

            val response =
                client.execute(
                    SecureHttpRequest(
                        url = "blocked.example",
                        echMode = SecureHttpEchMode.REQUIRE_CONFIRMED,
                    ),
                )

            assertEquals(OwnedStackBrowserBackend.NATIVE_OWNED_TLS, response.backend)
            assertEquals(
                OwnedStackNativeFallbackReason.ECH_CONFIRMATION_MISSING,
                response.executionTrace.nativeFallbackReason,
            )
            assertFalse(response.executionTrace.platformAttempted)
            assertEquals("https://blocked.example", nativeFetcher.requests.single().url)
        }

    @Test
    fun `browser service delegates through secure client and decodes response body`() =
        runTest {
            val browserService =
                DefaultOwnedStackBrowserService(
                    secureHttpClient =
                        FakeSecureHttpClient(
                            response =
                                SecureHttpResponse(
                                    requestedUrl = "https://example.org/",
                                    finalUrl = "https://example.org/final",
                                    statusCode = 200,
                                    body = "<html><body>Hello</body></html>".toByteArray(),
                                    contentType = "text/html; charset=UTF-8",
                                    backend = OwnedStackBrowserBackend.HTTP_ENGINE,
                                    android17EchEligible = true,
                                    executionTrace =
                                        OwnedStackExecutionTrace(
                                            platformAttempted = true,
                                            effectiveEchMode = SecureHttpEchMode.OPPORTUNISTIC,
                                        ),
                                ),
                        ),
                )

            val page = browserService.fetch("example.org")

            assertEquals(OwnedStackBrowserBackend.HTTP_ENGINE, page.backend)
            assertEquals("https://example.org/final", page.finalUrl)
            assertEquals("<html><body>Hello</body></html>", page.bodyText)
            assertTrue(page.executionTrace.platformAttempted)
        }

    @Test
    fun `launch helper normalizes authority to https url`() {
        assertEquals("https://example.org:443/", ownedStackBrowserLaunchUrl("example.org:443"))
    }

    @Test
    fun `normalizeUrl rejects non https schemes`() {
        val browserService =
            DefaultOwnedStackBrowserService(
                secureHttpClient = FakeSecureHttpClient(),
            )

        val error =
            runCatching { browserService.normalizeUrl("http://example.org") }
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
    private val block: suspend (OwnedStackPlatformRequest) -> OwnedStackPlatformResponse =
        {
            OwnedStackPlatformResponse(
                finalUrl = "https://example.org/",
                statusCode = 200,
                body = ByteArray(0),
                contentType = "text/plain",
            )
        },
) : OwnedStackPlatformBrowserExecutor {
    val requests = mutableListOf<OwnedStackPlatformRequest>()

    override suspend fun execute(request: OwnedStackPlatformRequest): OwnedStackPlatformResponse {
        requests += request
        return block(request)
    }
}

private class FakeSecureHttpClient(
    private val support: OwnedStackBrowserSupport = OwnedStackBrowserSupport(true, true),
    private val response: SecureHttpResponse =
        SecureHttpResponse(
            requestedUrl = "https://example.org/",
            finalUrl = "https://example.org/",
            statusCode = 200,
            body = ByteArray(0),
            contentType = "text/plain",
            backend = OwnedStackBrowserBackend.HTTP_ENGINE,
            android17EchEligible = true,
        ),
) : SecureHttpClient {
    override fun currentSupport(): OwnedStackBrowserSupport = support

    override fun normalizeUrl(rawUrl: String): String =
        rawUrl.trim().let { candidate ->
            require(candidate.isNotBlank()) { "Enter a URL to open in the RIPDPI browser." }
            val withScheme =
                if ("://" in candidate) {
                    candidate
                } else {
                    "https://$candidate"
                }
            val parsed = URI(withScheme)
            require(parsed.scheme.equals("https", ignoreCase = true)) {
                "Only HTTPS URLs are supported in the RIPDPI browser."
            }
            require(!parsed.host.isNullOrBlank()) { "Enter a valid HTTPS host." }
            parsed.toString()
        }

    override suspend fun execute(request: SecureHttpRequest): SecureHttpResponse = response
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
