package com.poyka.ripdpi.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.Base64

class NativeOwnedTlsHttpFetcherTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `execute decodes native response body`() =
        runTest {
            val expected = "catalog".toByteArray()
            val fetcher =
                DefaultNativeOwnedTlsHttpFetcher(
                    bindings =
                        FakeNativeOwnedTlsHttpFetcherBindings(
                            payload =
                                json.encodeToString(
                                    FakeNativeResponse(
                                        statusCode = 200,
                                        bodyBase64 = Base64.getEncoder().encodeToString(expected),
                                        finalUrl = "https://cdn.example.test/catalog.json",
                                        tlsProfileId = "chrome_stable",
                                        tlsProfileCatalogVersion = "v1",
                                        tlsJa3ParityTarget = "chrome-stable",
                                        tlsJa4ParityTarget = "chrome-stable",
                                        tlsBrowserFamily = "chrome",
                                        tlsBrowserTrack = "android-stable",
                                        tlsTemplateAlpn = "h2_http11",
                                        tlsTemplateExtensionOrderFamily = "chromium_permuted",
                                        tlsTemplateGreaseStyle = "chromium_single_grease",
                                        tlsTemplateSupportedGroupsProfile = "x25519_p256_p384",
                                        tlsTemplateKeyShareProfile = "x25519_primary",
                                        tlsTemplateRecordChoreography = "single_record",
                                        tlsTemplateEchCapable = false,
                                        clientHelloSizeHint = 512,
                                        clientHelloInvariantStatus = "avoids_blocked_517_byte_client_hello",
                                    ),
                                ),
                        ),
                )

            val result =
                fetcher.execute(
                    NativeOwnedTlsHttpRequest(
                        url = "https://raw.githubusercontent.com/example/catalog.json",
                        tlsProfileId = "chrome_stable",
                    ),
                )

            assertEquals(200, result.statusCode)
            assertEquals("https://cdn.example.test/catalog.json", result.finalUrl)
            assertArrayEquals(expected, result.body)
            assertEquals("chrome_stable", result.tlsProfileId)
            assertEquals("v1", result.tlsProfileCatalogVersion)
            assertEquals("chrome-stable", result.tlsJa3ParityTarget)
            assertEquals("chrome-stable", result.tlsJa4ParityTarget)
            assertEquals("chrome", result.tlsBrowserFamily)
            assertEquals("android-stable", result.tlsBrowserTrack)
            assertEquals("h2_http11", result.tlsTemplateAlpn)
            assertEquals("chromium_permuted", result.tlsTemplateExtensionOrderFamily)
            assertEquals("chromium_single_grease", result.tlsTemplateGreaseStyle)
            assertEquals("x25519_p256_p384", result.tlsTemplateSupportedGroupsProfile)
            assertEquals("x25519_primary", result.tlsTemplateKeyShareProfile)
            assertEquals("single_record", result.tlsTemplateRecordChoreography)
            assertEquals(false, result.tlsTemplateEchCapable)
            assertEquals(512, result.clientHelloSizeHint)
            assertEquals("avoids_blocked_517_byte_client_hello", result.clientHelloInvariantStatus)
        }

    @Test(expected = IOException::class)
    fun `execute surfaces native bridge errors`() =
        runTest {
            val fetcher =
                DefaultNativeOwnedTlsHttpFetcher(
                    bindings =
                        FakeNativeOwnedTlsHttpFetcherBindings(
                            payload = json.encodeToString(FakeNativeResponse(error = "TLS handshake failed")),
                        ),
                )

            fetcher.execute(
                NativeOwnedTlsHttpRequest(
                    url = "https://raw.githubusercontent.com/example/catalog.json",
                    tlsProfileId = "chrome_stable",
                ),
            )
        }

    @Test
    fun `execute tolerates missing mimicry metadata`() =
        runTest {
            val fetcher =
                DefaultNativeOwnedTlsHttpFetcher(
                    bindings =
                        FakeNativeOwnedTlsHttpFetcherBindings(
                            payload =
                                json.encodeToString(
                                    FakeNativeResponse(
                                        statusCode = 204,
                                        bodyBase64 = Base64.getEncoder().encodeToString(ByteArray(0)),
                                    ),
                                ),
                        ),
                )

            val result =
                fetcher.execute(
                    NativeOwnedTlsHttpRequest(
                        url = "https://raw.githubusercontent.com/example/catalog.json",
                        tlsProfileId = "chrome_stable",
                    ),
                )

            assertEquals(204, result.statusCode)
            assertNull(result.tlsProfileId)
            assertNull(result.tlsProfileCatalogVersion)
            assertNull(result.tlsJa3ParityTarget)
            assertNull(result.tlsJa4ParityTarget)
            assertNull(result.tlsBrowserFamily)
            assertNull(result.tlsBrowserTrack)
            assertNull(result.tlsTemplateAlpn)
            assertNull(result.tlsTemplateExtensionOrderFamily)
            assertNull(result.tlsTemplateGreaseStyle)
            assertNull(result.tlsTemplateSupportedGroupsProfile)
            assertNull(result.tlsTemplateKeyShareProfile)
            assertNull(result.tlsTemplateRecordChoreography)
            assertNull(result.tlsTemplateEchCapable)
            assertNull(result.clientHelloSizeHint)
            assertNull(result.clientHelloInvariantStatus)
        }

    private class FakeNativeOwnedTlsHttpFetcherBindings(
        private val payload: String?,
    ) : NativeOwnedTlsHttpFetcherBindings {
        override fun execute(requestJson: String): String? = payload
    }

    @Serializable
    private data class FakeNativeResponse(
        val statusCode: Int? = null,
        val bodyBase64: String? = null,
        val finalUrl: String? = null,
        val tlsProfileId: String? = null,
        val tlsProfileCatalogVersion: String? = null,
        val tlsJa3ParityTarget: String? = null,
        val tlsJa4ParityTarget: String? = null,
        val tlsBrowserFamily: String? = null,
        val tlsBrowserTrack: String? = null,
        val tlsTemplateAlpn: String? = null,
        val tlsTemplateExtensionOrderFamily: String? = null,
        val tlsTemplateGreaseStyle: String? = null,
        val tlsTemplateSupportedGroupsProfile: String? = null,
        val tlsTemplateKeyShareProfile: String? = null,
        val tlsTemplateRecordChoreography: String? = null,
        val tlsTemplateEchCapable: Boolean? = null,
        val clientHelloSizeHint: Int? = null,
        val clientHelloInvariantStatus: String? = null,
        val error: String? = null,
    )
}
