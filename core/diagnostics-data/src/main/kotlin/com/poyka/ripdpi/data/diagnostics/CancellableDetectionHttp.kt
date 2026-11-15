package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun executeBufferedCall(
    client: OkHttpClient,
    request: Request,
    maxResponseBytes: Long,
    proxy: Proxy?,
): DetectionTransportResponse {
    require(maxResponseBytes in 1 until Int.MAX_VALUE.toLong()) { "Invalid response byte limit" }
    val selectedClient = if (proxy == null) client else client.newBuilder().proxy(proxy).build()
    return suspendCancellableCoroutine { continuation ->
        val call = selectedClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        val result =
                            response.use {
                                val source = it.body.source()
                                if (source.request(maxResponseBytes + 1)) {
                                    throw IOException("Diagnostics response exceeds $maxResponseBytes bytes")
                                }
                                DetectionTransportResponse(it.code, source.readUtf8(), it.headers.toMultimap())
                            }
                        continuation.resume(result)
                    } catch (error: IOException) {
                        continuation.resumeWithException(error)
                    }
                }
            },
        )
    }
}
