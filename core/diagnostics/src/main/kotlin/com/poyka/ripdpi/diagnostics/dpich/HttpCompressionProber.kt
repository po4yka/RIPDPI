package com.poyka.ripdpi.diagnostics.dpich

import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.brotli.dec.BrotliInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

class HttpCompressionProber(
    private val includeZstd: Boolean = false,
    private val clientBuilder: (OkHttpClient.Builder.() -> Unit) -> OkHttpClient =
        { configure -> OkHttpClient.Builder().apply(configure).build() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun probeAll(
        url: String,
        timeoutMs: Long = DefaultTimeoutMs,
        comprMin: Int = DefaultCompressionMin,
        includeZstd: Boolean = this.includeZstd,
    ): Map<CompressionCodec, CompressionProbeResult> =
        coroutineScope {
            CompressionCodec
                .entries
                .associateWith { codec ->
                    async {
                        probeOne(
                            url = url,
                            codec = codec,
                            timeoutMs = timeoutMs,
                            comprMin = comprMin,
                            includeZstd = includeZstd,
                        )
                    }
                }.mapValues { (_, deferred) -> deferred.await() }
        }

    suspend fun probeOne(
        url: String,
        codec: CompressionCodec,
        timeoutMs: Long = DefaultTimeoutMs,
        comprMin: Int = DefaultCompressionMin,
        includeZstd: Boolean = this.includeZstd,
    ): CompressionProbeResult {
        if (codec == CompressionCodec.ZSTD && !includeZstd) {
            return CompressionProbeResult(
                verdict = CompressionProbeVerdict.NOT_SUPPORTED,
                decompressedBytes = 0,
                error = "ZSTD probe disabled",
            )
        }

        return withContext(ioDispatcher) {
            runCatching {
                executeProbe(
                    url = url,
                    codec = codec,
                    timeoutMs = timeoutMs,
                    comprMin = comprMin,
                )
            }.getOrElse { error ->
                when (error) {
                    is CancellationException -> {
                        throw error
                    }

                    is InterruptedIOException -> {
                        CompressionProbeResult(
                            verdict = CompressionProbeVerdict.TIMEOUT,
                            error = error.message,
                        )
                    }

                    is IOException -> {
                        CompressionProbeResult(CompressionProbeVerdict.CONN_ERR, error = error.message)
                    }

                    else -> {
                        CompressionProbeResult(CompressionProbeVerdict.INTERNAL_ERR, error = error.message)
                    }
                }
            }
        }
    }

    private fun executeProbe(
        url: String,
        codec: CompressionCodec,
        timeoutMs: Long,
        comprMin: Int,
    ): CompressionProbeResult {
        val client =
            clientBuilder(
                {
                    connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                },
            )
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept-Encoding", codec.acceptEncoding)
                .header("User-Agent", GenericChromeUserAgent)
                .get()
                .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body
            val contentEncoding = response.header("Content-Encoding").orEmpty()
            if (!codec.matchesContentEncoding(contentEncoding)) {
                return CompressionProbeResult(
                    verdict = CompressionProbeVerdict.NOT_SUPPORTED,
                    httpStatus = response.code,
                    compressedBytes = responseBody.contentLength().takeIf { it >= 0 },
                    decompressedBytes = 0,
                )
            }

            val raw = CountingInputStream(responseBody.byteStream())
            return decodeBody(
                codec = codec,
                raw = raw,
                httpStatus = response.code,
                comprMin = comprMin,
            )
        }
    }

    private fun decodeBody(
        codec: CompressionCodec,
        raw: CountingInputStream,
        httpStatus: Int,
        comprMin: Int,
    ): CompressionProbeResult {
        // Counted as the stream is read, so a failure mid-body still reports how far decoding got.
        var decompressedBytes = 0L

        fun result(
            failureVerdict: CompressionProbeVerdict,
            error: String? = null,
        ) = CompressionProbeResult(
            verdict = if (decompressedBytes >= comprMin) CompressionProbeVerdict.OK else failureVerdict,
            httpStatus = httpStatus,
            compressedBytes = raw.bytesRead,
            decompressedBytes = decompressedBytes,
            error = error,
        )

        return try {
            codec.decoder(raw).use { decoded ->
                val buffer = ByteArray(DecodeChunkBytes)
                while (true) {
                    val read = decoded.read(buffer)
                    if (read < 0) break
                    decompressedBytes += read.toLong()
                }
            }
            result(CompressionProbeVerdict.EOF_BEFORE_MIN)
        } catch (e: EOFException) {
            result(CompressionProbeVerdict.EOF_BEFORE_MIN, e.message)
        } catch (e: InterruptedIOException) {
            result(CompressionProbeVerdict.TIMEOUT, e.message)
        } catch (e: SocketException) {
            result(CompressionProbeVerdict.CONN_ERR, e.message)
        } catch (e: IOException) {
            result(CompressionProbeVerdict.INTERNAL_ERR, e.message)
        }
    }

    // RFC 9110 s8.4.1.3: x-gzip is the same coding as gzip.
    private fun CompressionCodec.matchesContentEncoding(contentEncoding: String): Boolean =
        contentEncoding.equals(acceptEncoding, ignoreCase = true) ||
            (this == CompressionCodec.GZIP && contentEncoding.equals("x-gzip", ignoreCase = true))

    private fun CompressionCodec.decoder(raw: InputStream): InputStream =
        when (this) {
            CompressionCodec.GZIP -> GZIPInputStream(raw)
            CompressionCodec.DEFLATE -> InflaterInputStream(raw)
            CompressionCodec.BROTLI -> BrotliInputStream(raw)
            CompressionCodec.ZSTD -> ZstdInputStream(raw)
        }

    private class CountingInputStream(
        source: InputStream,
    ) : FilterInputStream(source) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) bytesRead += read.toLong()
            return read
        }
    }

    private companion object {
        const val DefaultTimeoutMs = 10_000L
        const val DefaultCompressionMin = 1_024
        const val DecodeChunkBytes = 8 * 1_024
        const val GenericChromeUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
