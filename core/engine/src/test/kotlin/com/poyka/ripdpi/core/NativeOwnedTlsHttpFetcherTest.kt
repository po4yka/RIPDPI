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
        val clientHelloSizeHint: Int? = null,
        val clientHelloInvariantStatus: String? = null,
        val error: String? = null,
    )
}
