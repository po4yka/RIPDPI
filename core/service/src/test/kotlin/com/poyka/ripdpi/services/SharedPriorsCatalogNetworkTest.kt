package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SharedPriorsCatalogNetworkTest {
    @Test
    fun `priors payload at limit is returned byte-identical`() =
        runTest {
            val expected = ByteArray(TestPayloadMaxBytes) { index -> (index % 251).toByte() }

            withService(knownLengthResponse(expected)) { service, url ->
                assertArrayEquals(expected, service.downloadPriors(url))
            }
        }

    @Test
    fun `known-length priors payload over limit is rejected`() =
        runTest {
            val response = knownLengthResponse(ByteArray(TestPayloadMaxBytes + 1))

            withService(response) { service, url ->
                val error = captureIOException { service.downloadPriors(url) }

                assertOverflowMessage(error, label = "payload", overflowKind = "declared", limit = TestPayloadMaxBytes)
            }
        }

    @Test
    fun `chunked priors payload over limit is rejected`() =
        runTest {
            val response = chunkedResponse(ByteArray(TestPayloadMaxBytes + 1))

            withService(response) { service, url ->
                val error = captureIOException { service.downloadPriors(url) }

                assertOverflowMessage(error, label = "payload", overflowKind = "streamed", limit = TestPayloadMaxBytes)
            }
        }

    @Test
    fun `manifest at limit preserves UTF-8 text`() =
        runTest {
            val expected = "Ж" + "m".repeat(TestManifestMaxBytes - 2)
            assertEquals(TestManifestMaxBytes, expected.toByteArray(Charsets.UTF_8).size)

            withService(knownLengthResponse(expected.toByteArray(Charsets.UTF_8))) { service, url ->
                assertEquals(expected, service.downloadManifest(url))
            }
        }

    @Test
    fun `known-length manifest over limit is rejected`() =
        runTest {
            val response = knownLengthResponse(ByteArray(TestManifestMaxBytes + 1))

            withService(response) { service, url ->
                val error = captureIOException { service.downloadManifest(url) }

                assertOverflowMessage(
                    error,
                    label = "manifest",
                    overflowKind = "declared",
                    limit = TestManifestMaxBytes,
                )
            }
        }

    @Test
    fun `chunked manifest over limit is rejected`() =
        runTest {
            val response = chunkedResponse(ByteArray(TestManifestMaxBytes + 1))

            withService(response) { service, url ->
                val error = captureIOException { service.downloadManifest(url) }

                assertOverflowMessage(
                    error,
                    label = "manifest",
                    overflowKind = "streamed",
                    limit = TestManifestMaxBytes,
                )
            }
        }

    @Test
    fun `HTTP failure takes precedence over body limit`() =
        runTest {
            val response =
                MockResponse
                    .Builder()
                    .code(503)
                    .body(Buffer().write(ByteArray(TestManifestMaxBytes + 1)))
                    .build()

            withService(response) { service, url ->
                val error = captureIOException { service.downloadManifest(url) }

                assertTrue(error.message.orEmpty().contains("HTTP 503"))
                assertTrue(error.message.orEmpty().contains(url))
            }
        }

    private suspend fun withService(
        response: MockResponse,
        block: suspend (DefaultSharedPriorsCatalogDownloadService, String) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            val url = server.url("/shared-priors").toString()
            block(DefaultSharedPriorsCatalogDownloadService(TestOwnedTlsClientFactory()), url)
        }
    }

    private suspend fun captureIOException(block: suspend () -> Unit): IOException =
        try {
            block()
            fail("Expected IOException")
            error("unreachable")
        } catch (error: IOException) {
            error
        }

    private fun assertOverflowMessage(
        error: IOException,
        label: String,
        overflowKind: String,
        limit: Int,
    ) {
        val message = error.message.orEmpty()
        assertTrue(message.contains("shared-priors"))
        assertTrue(message.contains(label))
        assertTrue(message.contains(limit.toString()))
        assertTrue(message.contains(overflowKind))
    }

    private fun knownLengthResponse(payload: ByteArray): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .body(Buffer().write(payload))
            .build()

    private fun chunkedResponse(payload: ByteArray): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .chunkedBody(Buffer().write(payload), TestChunkBytes)
            .build()

    private class TestOwnedTlsClientFactory : OwnedTlsClientFactory {
        private val selection = OwnedTlsFingerprintSelection()

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
        ): OkHttpClient = OkHttpClient.Builder().apply(configure).build()
    }

    private companion object {
        const val TestManifestMaxBytes = 64 * 1024
        const val TestPayloadMaxBytes = 256 * 1024
        const val TestChunkBytes = 8 * 1024
    }
}
