package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpTelegramDownloadClient(
    client: OkHttpClient = OkHttpClient(),
    private val url: String = "https://telegram.org/img/Telegram200million.png",
    private val sampleBytes: Long = TelegramSpeedTest.DownloadBytes,
) : TelegramTransferClient {
    private val client = client.forTransfer()

    init {
        require(sampleBytes in 1..TelegramSpeedTest.DownloadBytes)
    }

    override suspend fun transfer(observer: TelegramTransferObserver) {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .get()
                .build()
        client.newCall(request).readTransferResponse { response ->
            observer.responseHeaders(response.code)
            if (response.code == HttpFullResponse) readPayload(response, observer)
        }
    }

    private fun readPayload(
        response: Response,
        observer: TelegramTransferObserver,
    ) {
        if (response.headers("Content-Encoding").any { !it.equals("identity", ignoreCase = true) }) {
            throw ProtocolException("Telegram download response uses an unexpected content encoding")
        }
        val input = response.body.byteStream()
        if (input.read() < 0) return
        observer.payloadStarted()
        observer.transferred(1)
        val buffer = ByteArray(DownloadBufferBytes)
        var remaining = sampleBytes - 1
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) break
            observer.transferred(read)
            remaining -= read
        }
    }
}

class OkHttpTelegramUploadClient(
    client: OkHttpClient = OkHttpClient(),
    private val url: String = "https://149.154.167.220:443/upload",
) : TelegramTransferClient {
    private val client = client.forTransfer()

    override suspend fun transfer(observer: TelegramTransferObserver) {
        val request =
            Request
                .Builder()
                .url(url)
                .post(CountingZeroRequestBody(observer))
                .build()
        client.newCall(request).readTransferResponse { response ->
            observer.responseHeaders(response.code)
        }
    }
}

private class CountingZeroRequestBody(
    private val observer: TelegramTransferObserver,
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = TelegramSpeedTest.UploadBytes

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val chunk = ByteArray(UploadChunkBytes)
        var remaining = contentLength()
        observer.payloadStarted()
        while (remaining > 0) {
            val toWrite = minOf(chunk.size.toLong(), remaining).toInt()
            sink.write(chunk, 0, toWrite)
            observer.transferred(toWrite)
            remaining -= toWrite
        }
    }
}

private suspend fun Call.readTransferResponse(read: (Response) -> Unit) {
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                execute().use(read)
                continuation.resume(Unit)
            } catch (error: IOException) {
                continuation.resumeWithException(error)
            } catch (error: SecurityException) {
                continuation.resumeWithException(error)
            }
        }
    }
}

private const val DownloadBufferBytes = 64 * 1_024
private const val UploadChunkBytes = 16 * 1_024

private fun OkHttpClient.forTransfer(): OkHttpClient =
    newBuilder()
        .connectTimeout(TelegramSpeedTest.StallTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(TelegramSpeedTest.StallTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(TelegramSpeedTest.StallTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(TelegramSpeedTest.TotalTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
