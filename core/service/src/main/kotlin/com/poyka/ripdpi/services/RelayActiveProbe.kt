package com.poyka.ripdpi.services

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class RelayActiveProbeResult(
    val succeeded: Boolean,
    val statusCode: Int? = null,
    val latencyMs: Long,
    val failure: String? = null,
)

internal fun interface RelayActiveProbe {
    suspend fun probe(
        endpoint: LocalProxyEndpoint,
        url: String,
    ): RelayActiveProbeResult
}

internal class OkHttpRelayActiveProbe : RelayActiveProbe {
    override suspend fun probe(
        endpoint: LocalProxyEndpoint,
        url: String,
    ): RelayActiveProbeResult {
        val client =
            OkHttpClient
                .Builder()
                .proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress.createUnresolved(endpoint.host, endpoint.port),
                    ),
                ).followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(ProbeTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        val startedAt = System.nanoTime()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(
                                RelayActiveProbeResult(
                                    succeeded = false,
                                    latencyMs = elapsedMillis(startedAt),
                                    failure = if (call.isCanceled()) "cancelled" else "io_error",
                                ),
                            )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            if (continuation.isActive) {
                                continuation.resume(
                                    RelayActiveProbeResult(
                                        succeeded = response.code in SuccessfulStatusRange,
                                        statusCode = response.code,
                                        latencyMs = elapsedMillis(startedAt),
                                        failure =
                                            if (response.code in SuccessfulStatusRange) {
                                                null
                                            } else {
                                                "http_status"
                                            },
                                    ),
                                )
                            }
                        }
                    }
                },
            )
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt).coerceAtLeast(0L)

    private companion object {
        const val ProbeTimeoutSeconds = 10L
        val SuccessfulStatusRange = 200..299
    }
}
