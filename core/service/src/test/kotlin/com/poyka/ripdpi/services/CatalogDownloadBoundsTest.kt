package com.poyka.ripdpi.services

import com.poyka.ripdpi.assets.DefaultGeoAssetDownloadService
import com.poyka.ripdpi.assets.GeoAssetMetadataMaxBytes
import com.poyka.ripdpi.hosts.DefaultHostPackCatalogDownloadService
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.TlsVersion
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CatalogDownloadBoundsTest {
    @Test
    fun `manifest success and error reads stop before Retrofit buffers the whole body`() =
        runTest {
            for (kind in listOf("priors", "host", "geo")) {
                for (status in listOf(200, 500)) {
                    for (chunked in listOf(false, true)) {
                        assertManifestBound(kind, status, chunked)
                    }
                }
            }
        }

    private suspend fun assertManifestBound(
        kind: String,
        status: Int,
        chunked: Boolean,
    ) {
        val readBytes = AtomicLong()
        MockWebServer().use { server ->
            val payload = Buffer().write(ByteArray(TestOversizedBytes))
            val response = MockResponse.Builder().code(status)
            if (chunked) response.chunkedBody(payload, TestBufferBytes) else response.body(payload)
            server.enqueue(response.build())
            server.start()
            val url = server.url("/manifest")
            val factory = observingFactory(readBytes, url)
            val limit =
                if (status != 200 ||
                    kind == "priors"
                ) {
                    SharedPriorsManifestMaxBytes.toLong()
                } else {
                    GeoAssetMetadataMaxBytes
                }
            try {
                when (kind) {
                    "host" -> DefaultHostPackCatalogDownloadService(factory).downloadManifest()
                    "geo" -> DefaultGeoAssetDownloadService(factory).fetchLatestReleaseJson(url.toString())
                    else -> DefaultSharedPriorsCatalogDownloadService(factory).downloadManifest(url.toString())
                }
                fail("Expected an oversized response to fail")
            } catch (error: IOException) {
                val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString()
                val rejectedAtHeaders = kind == "priors" && status != 200
                if (rejectedAtHeaders) {
                    assertTrue(messages, messages.contains("Remote request failed with HTTP $status"))
                    assertEquals("shared-priors errors must not read the body", 0L, readBytes.get())
                } else {
                    assertTrue(messages, messages.contains("byte limit $limit"))
                }
                assertTrue(messages, messages.contains("HTTP $status"))
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                if (chunked && !rejectedAtHeaders) {
                    assertTrue("chunked response must reach the limit", readBytes.get() > limit)
                }
                assertTrue(
                    "$kind HTTP $status chunked=$chunked read ${readBytes.get()} bytes before rejection",
                    readBytes.get() <= limit + TestBufferBytes,
                )
            }
        }
    }

    private fun observingFactory(
        readBytes: AtomicLong,
        target: HttpUrl,
    ): OwnedTlsClientFactory =
        object : OwnedTlsClientFactory {
            override fun currentProfile(): String = "chrome_stable"

            override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection =
                OwnedTlsFingerprintSelection()

            override fun create(
                forcedTlsVersions: List<TlsVersion>?,
                configure: OkHttpClient.Builder.() -> Unit,
            ): OkHttpClient = client(configure)

            override fun createForAuthority(
                authority: String?,
                forcedTlsVersions: List<TlsVersion>?,
                configure: OkHttpClient.Builder.() -> Unit,
            ): OkHttpClient = client(configure)

            private fun client(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .url(target)
                                .build(),
                        )
                    }.addNetworkInterceptor { chain ->
                        val response = chain.proceed(chain.request())
                        val body = response.body
                        val source =
                            object : ForwardingSource(body.source()) {
                                override fun read(
                                    sink: Buffer,
                                    byteCount: Long,
                                ): Long = super.read(sink, byteCount).also { if (it > 0) readBytes.addAndGet(it) }
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
                    }.apply(configure)
                    .build()
        }

    private companion object {
        const val TestOversizedBytes = 2 * 1024 * 1024
        const val TestBufferBytes = 8192
    }
}
