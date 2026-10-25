package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.PayloadProbeControl
import com.poyka.ripdpi.data.ProfileQualityMeasurement
import com.poyka.ripdpi.data.ProfileQualityReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Authenticated transaction timings; no bandwidth throttling is used to manufacture overlap. */
internal class QualityHttpPayloadProbe(
    private val timeoutMillis: Long,
    private val clientBuilder: () -> OkHttpClient.Builder = { OkHttpClient.Builder() },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun measure(
        endpoint: RelayProbeEndpoint,
        control: PayloadProbeControl,
        probeIndex: Int,
    ): ProfileQualityMeasurement? =
        coroutineScope {
            val client =
                clientBuilder()
                    .localSocksProxy(endpoint)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .build()
            try {
                val baseline = head(client, control)
                val bodyStarted = CompletableDeferred<Boolean>()
                val loaded = async { if (bodyStarted.await()) head(client, control) else null }
                val transfer =
                    request(client, control, head = false) { response ->
                        var start: Long? = null
                        var end = 0L
                        val verified =
                            consumeVerifiedPayload(
                                response,
                                control,
                                onBodyReadStarted = {
                                    start = nanoTime()
                                    bodyStarted.complete(true)
                                },
                                onBodyReadFinished = { end = nanoTime() },
                            )
                        val began = start
                        if (verified && began != null && end > began) TimedInterval(began, end) else null
                    }
                bodyStarted.complete(false)
                val load = loaded.await()
                transfer?.let {
                    val overlap = load != null && load.start >= it.start && load.end <= it.end
                    val reason =
                        when {
                            baseline == null -> ProfileQualityReason.BaselineUnavailable
                            load == null -> ProfileQualityReason.LoadedUnavailable
                            !overlap -> ProfileQualityReason.LoadNotOverlapped
                            else -> ProfileQualityReason.Verified
                        }
                    ProfileQualityMeasurement(
                        probeIndex,
                        control.expectedBytes.toLong(),
                        it.end - it.start,
                        baseline?.millis,
                        load?.takeIf { overlap }?.millis,
                        overlap,
                        reason,
                    )
                }
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    private suspend fun head(
        client: OkHttpClient,
        control: PayloadProbeControl,
    ): TimedInterval? {
        val start = nanoTime()
        return request(client, control, head = true) { response ->
            val valid =
                response.code == HttpOk && response.header("Content-Range") == null &&
                    response.header("Content-Encoding")?.equals("identity", ignoreCase = true) != false &&
                    response.header("Content-Length")?.toLongOrNull() == control.expectedBytes.toLong()
            if (valid) TimedInterval(start, nanoTime()) else null
        }
    }

    private suspend fun <T> request(
        client: OkHttpClient,
        control: PayloadProbeControl,
        head: Boolean,
        consume: (Response) -> T?,
    ): T? =
        suspendCancellableCoroutine { continuation ->
            val request =
                Request
                    .Builder()
                    .url(control.url)
                    .header("Accept-Encoding", "identity")
                    .apply { if (head) head() else get() }
                    .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            try {
                                response.use(consume)
                            } catch (_: IOException) {
                                null
                            }
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }

    private data class TimedInterval(
        val start: Long,
        val end: Long,
    ) {
        val millis: Long get() = (end - start) / NanosPerMillisecond
    }
}

private const val HttpOk = 200
private const val NanosPerMillisecond = 1_000_000L
