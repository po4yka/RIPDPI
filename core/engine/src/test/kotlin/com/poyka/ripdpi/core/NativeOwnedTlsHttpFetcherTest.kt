package com.poyka.ripdpi.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val error: String? = null,
    )
}
