package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import com.poyka.ripdpi.core.NativeOwnedTlsHttpResult
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import com.poyka.ripdpi.services.OwnedTlsFingerprintSelection
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.IOException

class StrategyPackNetworkTest {
    @Test
    fun `download catalog accepts matching ECH-aware template metadata`() =
        runTest {
            val expected = "catalog".toByteArray()
            val service =
                DefaultStrategyPackDownloadService(
                    nativeOwnedTlsHttpFetcher =
                        FakeNativeOwnedTlsHttpFetcher(
                            result =
                                NativeOwnedTlsHttpResult(
                                    statusCode = 200,
                                    body = expected,
                                    finalUrl = "https://cdn.example/catalog.json",
                                    tlsProfileId = "firefox_ech_stable",
                                    tlsTemplateEchCapable = true,
                                    tlsTemplateEchBootstrapPolicy = "https_rr_or_cdn_fallback",
                                    tlsTemplateEchBootstrapResolverId = "adguard",
                                    tlsTemplateEchOuterExtensionPolicy = "preserve_ech_or_grease",
                                ),
                        ),
                    tlsClientFactory =
                        FakeOwnedTlsClientFactory(
                            selection =
                                OwnedTlsFingerprintSelection(
                                    profileId = "firefox_ech_stable",
                                    echPolicy = "preferred",
                                    tlsTemplateEchCapable = true,
                                    tlsTemplateEchBootstrapPolicy = "https_rr_or_cdn_fallback",
                                    tlsTemplateEchBootstrapResolverId = "adguard",
                                    tlsTemplateEchOuterExtensionPolicy = "preserve_ech_or_grease",
                                ),
                        ),
                )

            val payload = service.downloadCatalog("https://example.test/catalog.json")

            assertArrayEquals(expected, payload)
        }

    @Test(expected = IOException::class)
    fun `download catalog rejects ECH metadata drift`() =
        runTest {
            val service =
                DefaultStrategyPackDownloadService(
                    nativeOwnedTlsHttpFetcher =
                        FakeNativeOwnedTlsHttpFetcher(
                            result =
                                NativeOwnedTlsHttpResult(
                                    statusCode = 200,
                                    body = "catalog".toByteArray(),
                                    finalUrl = "https://cdn.example/catalog.json",
                                    tlsProfileId = "firefox_ech_stable",
                                    tlsTemplateEchCapable = false,
                                ),
                        ),
                    tlsClientFactory =
                        FakeOwnedTlsClientFactory(
                            selection =
                                OwnedTlsFingerprintSelection(
                                    profileId = "firefox_ech_stable",
                                    echPolicy = "preferred",
                                    tlsTemplateEchCapable = true,
                                    tlsTemplateEchBootstrapPolicy = "https_rr_or_cdn_fallback",
                                    tlsTemplateEchBootstrapResolverId = "adguard",
                                    tlsTemplateEchOuterExtensionPolicy = "preserve_ech_or_grease",
                                ),
                        ),
                )

            service.downloadCatalog("https://example.test/catalog.json")
        }

    private class FakeNativeOwnedTlsHttpFetcher(
        private val result: NativeOwnedTlsHttpResult,
    ) : NativeOwnedTlsHttpFetcher {
        override suspend fun execute(request: NativeOwnedTlsHttpRequest): NativeOwnedTlsHttpResult = result
    }

    private class FakeOwnedTlsClientFactory(
        private val selection: OwnedTlsFingerprintSelection,
    ) : OwnedTlsClientFactory {
        override fun currentProfile(): String = selection.profileId

        override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection = selection

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = OkHttpClient.Builder().apply(configure).build()

        override fun createForAuthority(
            authority: String?,
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = create(forcedTlsVersions, configure)
    }
}
