package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

class HttpCompressionProberTest {
    @Test
    fun gzipWithFullPayloadReturnsOk() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(codecResponse("gzip", gzip(payloadBytes())))
                server.start()

                val result = prober(includeZstd = false).probeOne(server.url("/gzip").toString(), CompressionCodec.GZIP)

                assertEquals(CompressionProbeVerdict.OK, result.verdict)
                assertEquals(payloadBytes().size.toLong(), result.decompressedBytes)
                assertTrue(result.compressedBytes != null && result.compressedBytes > 0L)
            }
        }

    @Test
    fun deflateWithPayloadReturnsOk() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(codecResponse("deflate", deflate(payloadBytes())))
                server.start()

                val result =
                    prober(includeZstd = false).probeOne(
                        server.url("/deflate").toString(),
                        CompressionCodec.DEFLATE,
                    )

                assertEquals(CompressionProbeVerdict.OK, result.verdict)
                assertEquals(payloadBytes().size.toLong(), result.decompressedBytes)
            }
        }

    @Test
    fun brotliWithPayloadReturnsOk() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(codecResponse("br", brotliPayload()))
                server.start()

                val result =
                    prober(includeZstd = false).probeOne(
                        server.url("/br").toString(),
                        CompressionCodec.BROTLI,
                    )

                assertEquals(CompressionProbeVerdict.OK, result.verdict)
                assertEquals(payloadBytes().size.toLong(), result.decompressedBytes)
            }
        }

    @Test
    fun unsupportedCodecReturnsNotSupported() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("identity")
                        .build(),
                )
                server.start()

                val result =
                    prober(includeZstd = false).probeOne(
                        server.url("/identity").toString(),
                        CompressionCodec.BROTLI,
                    )

                assertEquals(CompressionProbeVerdict.NOT_SUPPORTED, result.verdict)
                assertEquals(200, result.httpStatus)
                assertEquals(0L, result.decompressedBytes)
            }
        }

    @Test
    fun payloadBelowComprMinReturnsEofBeforeMin() =
        runTest {
            val body = "x".repeat(200).encodeToByteArray()
            MockWebServer().use { server ->
                server.enqueue(codecResponse("gzip", gzip(body)))
                server.start()

                val result =
                    prober(includeZstd = false)
                        .probeOne(server.url("/small").toString(), CompressionCodec.GZIP, comprMin = 1024)

                assertEquals(CompressionProbeVerdict.EOF_BEFORE_MIN, result.verdict)
                assertEquals(200L, result.decompressedBytes)
            }
        }

    @Test
    fun streamCutAfterComprMinKeepsTheDecodedCount() =
        runTest {
            val payload = kotlin.random.Random(1).nextBytes(64 * 1024)
            val compressed = gzip(payload)
            MockWebServer().use { server ->
                server.enqueue(codecResponse("gzip", compressed.copyOf(compressed.size / 2)))
                server.start()

                val result =
                    prober(includeZstd = false)
                        .probeOne(server.url("/cut").toString(), CompressionCodec.GZIP, comprMin = 1024)

                assertEquals(CompressionProbeVerdict.OK, result.verdict)
                assertTrue("decoded ${result.decompressedBytes}", (result.decompressedBytes ?: 0L) >= 1024L)
            }
        }

    @Test
    fun xGzipContentEncodingIsDecodedAsGzip() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(codecResponse("x-gzip", gzip(payloadBytes())))
                server.start()

                val result =
                    prober(
                        includeZstd = false,
                    ).probeOne(server.url("/x-gzip").toString(), CompressionCodec.GZIP)

                assertEquals(CompressionProbeVerdict.OK, result.verdict)
            }
        }

    @Test
    fun bodyThatStallsReturnsTimeout() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Encoding", "gzip")
                        .bodyDelay(2, TimeUnit.SECONDS)
                        .body(Buffer().write(gzip(payloadBytes())))
                        .build(),
                )
                server.start()

                val result =
                    prober(includeZstd = false)
                        .probeOne(server.url("/stall").toString(), CompressionCodec.GZIP, timeoutMs = 300)

                assertEquals(CompressionProbeVerdict.TIMEOUT, result.verdict)
            }
        }

    @Test
    fun timeoutReturnsTimeout() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .headersDelay(1, TimeUnit.SECONDS)
                        .body("late")
                        .build(),
                )
                server.start()

                val result =
                    prober(includeZstd = false)
                        .probeOne(server.url("/timeout").toString(), CompressionCodec.GZIP, timeoutMs = 100)

                assertEquals(CompressionProbeVerdict.TIMEOUT, result.verdict)
            }
        }

    @Test
    fun parallelProbesCompleteIndependently() =
        runTest {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.headers["Accept-Encoding"]) {
                                "gzip" -> codecResponse("gzip", gzip(payloadBytes()))
                                "deflate" -> codecResponse("deflate", deflate(payloadBytes()))
                                "br" -> codecResponse("br", brotliPayload())
                                else -> MockResponse.Builder().code(500).build()
                            }
                    }
                server.start()

                val result = prober(includeZstd = false).probeAll(server.url("/parallel").toString())

                assertEquals(CompressionProbeVerdict.OK, result.getValue(CompressionCodec.GZIP).verdict)
                assertEquals(CompressionProbeVerdict.OK, result.getValue(CompressionCodec.DEFLATE).verdict)
                assertEquals(CompressionProbeVerdict.OK, result.getValue(CompressionCodec.BROTLI).verdict)
                assertEquals(CompressionProbeVerdict.NOT_SUPPORTED, result.getValue(CompressionCodec.ZSTD).verdict)
            }
        }

    @Test
    fun zstdDisabledSettingReturnsNotSupportedWithoutNetwork() =
        runTest {
            MockWebServer().use { server ->
                server.start()

                val result = prober(includeZstd = false).probeOne(server.url("/zstd").toString(), CompressionCodec.ZSTD)

                assertEquals(CompressionProbeVerdict.NOT_SUPPORTED, result.verdict)
                assertEquals("ZSTD probe disabled", result.error)
                assertEquals(0, server.requestCount)
            }
        }

    @Test
    fun perCodecRequestUsesOnlyRequestedAcceptEncoding() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(codecResponse("gzip", gzip(payloadBytes())))
                server.start()

                prober(includeZstd = false).probeOne(
                    server.url("/headers").toString(),
                    CompressionCodec.GZIP,
                )

                val request = server.takeRequest()
                assertEquals("gzip", request.headers["Accept-Encoding"])
                assertFalse(request.headers["Accept-Encoding"].orEmpty().contains(","))
                assertTrue(request.headers["User-Agent"].orEmpty().contains("Chrome"))
            }
        }

    private fun prober(includeZstd: Boolean): HttpCompressionProber = HttpCompressionProber(includeZstd = includeZstd)

    private fun payloadBytes(): ByteArray = "x".repeat(4096).encodeToByteArray()

    private fun codecResponse(
        encoding: String,
        body: ByteArray,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .setHeader("Content-Encoding", encoding)
            .body(Buffer().write(body))
            .build()

    private fun gzip(body: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(body) }
            output.toByteArray()
        }

    private fun deflate(body: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(body)
        deflater.finish()
        return ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()
            output.toByteArray()
        }
    }

    private fun brotliPayload(): ByteArray = Base64.getDecoder().decode("G/8P+CXw4rFAIPcAAA==")
}
