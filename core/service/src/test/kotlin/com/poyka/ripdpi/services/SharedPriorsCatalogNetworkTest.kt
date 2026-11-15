package com.poyka.ripdpi.services

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.TlsVersion
import okio.Buffer
import okio.ForwardingSource
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SharedPriorsCatalogNetworkTest {
    @Test
    fun `priors payload at limit is returned byte-identical`() =
        runTest {
            val expected = ByteArray(TestPayloadMaxBytes) { index -> (index % 251).toByte() }

            val observation = BodyReadObservation()
            withService(knownLengthResponse(expected), observation) { service, url ->
                assertArrayEquals(expected, service.downloadPriors(url))
                assertTrue(observation.closed.get())
                assertFalse(observation.cancelled.get())
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
    fun `bounded HTTP failure preserves status and URL`() =
        runTest {
            val response =
                MockResponse
                    .Builder()
                    .code(503)
                    .body("Unavailable")
                    .build()

            withService(response) { service, url ->
                val error = captureIOException { service.downloadManifest(url) }

                assertTrue(error.message.orEmpty().contains("HTTP 503"))
                assertTrue(error.message.orEmpty().contains(url))
            }
        }

    @Test
    fun `oversized success and error bodies stop reading for both endpoints`() {
        for (manifest in listOf(true, false)) {
            for (framing in listOf("known", "chunked", "gzip")) {
                for (code in listOf(200, 503)) {
                    verifyBoundedResponse(manifest, framing, code)
                }
            }
        }
    }

    private fun verifyBoundedResponse(
        manifest: Boolean,
        framing: String,
        code: Int,
    ) = runTest {
        val limit = if (manifest) TestManifestMaxBytes else TestPayloadMaxBytes
        val observation = BodyReadObservation()
        val response = largeResponse(ByteArray(limit * 8), framing, code)

        withService(response, observation) { service, url ->
            val error =
                captureIOException {
                    if (manifest) service.downloadManifest(url) else service.downloadPriors(url)
                }
            val case = "manifest=$manifest framing=$framing code=$code"
            assertTrue(
                "$case read ${observation.bytes.get()} bytes",
                observation.bytes.get() <= limit + TestChunkBytes,
            )
            assertTrue("$case did not close body", observation.closed.get())
            assertTrue("$case did not cancel unread body", observation.cancelled.get())
            if (code == 503) assertTrue(error.message.orEmpty().contains("HTTP 503"))
        }
    }

    private fun largeResponse(
        payload: ByteArray,
        framing: String,
        code: Int,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(code)
            .apply {
                when (framing) {
                    "chunked" -> {
                        chunkedBody(Buffer().write(payload), TestChunkBytes)
                    }

                    "gzip" -> {
                        val compressed = Buffer()
                        GzipSink(compressed).buffer().use { it.write(payload) }
                        addHeader("Content-Encoding", "gzip")
                        body(compressed)
                    }

                    else -> {
                        body(Buffer().write(payload))
                    }
                }
            }.build()

    private suspend fun withService(
        response: MockResponse,
        observation: BodyReadObservation? = null,
        block: suspend (DefaultSharedPriorsCatalogDownloadService, String) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            val url = server.url("/shared-priors").toString()
            block(DefaultSharedPriorsCatalogDownloadService(TestOwnedTlsClientFactory(observation)), url)
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

    private class TestOwnedTlsClientFactory(
        private val observation: BodyReadObservation? = null,
    ) : OwnedTlsClientFactory {
        private val selection = OwnedTlsFingerprintSelection()

        override fun currentProfile(): String = selection.profileId

        override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection = selection

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = configuredClient(configure)

        override fun createForAuthority(
            authority: String?,
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient = configuredClient(configure)

        private fun configuredClient(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
            OkHttpClient
                .Builder()
                .apply(configure)
                .apply {
                    observation?.let { observed ->
                        addInterceptor { chain ->
                            val response = chain.proceed(chain.request())
                            val body = response.body
                            val source =
                                object : ForwardingSource(body.source()) {
                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        val count = super.read(sink, byteCount)
                                        if (count > 0L) observed.bytes.addAndGet(count)
                                        return count
                                    }

                                    override fun close() {
                                        try {
                                            super.close()
                                        } finally {
                                            observed.closed.set(true)
                                            observed.cancelled.set(chain.call().isCanceled())
                                        }
                                    }
                                }.buffer()
                            response
                                .newBuilder()
                                .body(
                                    object : ResponseBody() {
                                        override fun contentType() = body.contentType()

                                        override fun contentLength() = body.contentLength()

                                        override fun source() = source
                                    },
                                ).build()
                        }
                    }
                }.build()
    }

    private class BodyReadObservation {
        val bytes = AtomicLong()
        val closed = AtomicBoolean()
        val cancelled = AtomicBoolean()
    }

    private companion object {
        const val TestManifestMaxBytes = 64 * 1024
        const val TestPayloadMaxBytes = 256 * 1024
        const val TestChunkBytes = 8 * 1024
    }
}
