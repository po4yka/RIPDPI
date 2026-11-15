package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object IfconfigClient {
    private const val DefaultTimeoutMs = 7_000
    private const val MaxResponseBytes = 256L
    private val endpoint = "https://ifconfig.me/ip".toHttpUrl()
    private val httpClient = OkHttpClient()

    suspend fun fetchDirectIp(
        dispatchers: AppCoroutineDispatchers,
        timeoutMs: Int = DefaultTimeoutMs,
    ): Result<String> = fetchIp(dispatchers = dispatchers, timeoutMs = timeoutMs)

    suspend fun fetchIpViaProxy(
        dispatchers: AppCoroutineDispatchers,
        endpoint: ProxyEndpoint,
        timeoutMs: Int = DefaultTimeoutMs,
    ): Result<String> =
        fetchIp(
            dispatchers = dispatchers,
            timeoutMs = timeoutMs,
            proxy =
                Proxy(
                    when (endpoint.type) {
                        ProxyType.SOCKS5 -> Proxy.Type.SOCKS
                        ProxyType.HTTP -> Proxy.Type.HTTP
                    },
                    InetSocketAddress(endpoint.host, endpoint.port),
                ),
        )

    internal suspend fun fetchIp(
        dispatchers: AppCoroutineDispatchers,
        timeoutMs: Int,
        proxy: Proxy? = null,
        url: HttpUrl = endpoint,
        client: OkHttpClient = httpClient,
    ): Result<String> =
        withContext(dispatchers.io) {
            require(timeoutMs > 0)
            val builder = client.newBuilder().proxy(proxy)
            if (proxy == null) {
                val systemSelector = ProxySelector.getDefault()
                if (systemSelector == null) builder.proxy(Proxy.NO_PROXY) else builder.proxySelector(systemSelector)
            }
            val call =
                builder
                    .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                    .followSslRedirects(false)
                    .build()
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .header("Accept", "text/plain")
                            .build(),
                    )

            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            continuation.resume(Result.failure(e))
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            continuation.resume(readIp(response))
                        }
                    },
                )
            }
        }

    private fun readIp(response: Response): Result<String> =
        try {
            response.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                val source = it.body.source()
                if (source.request(MaxResponseBytes + 1)) throw IOException("IP response too large")
                val ip = source.readUtf8().trim()
                if (ip.isEmpty()) throw IOException("Empty response body")
                Result.success(ip)
            }
        } catch (error: IOException) {
            Result.failure(error)
        }
}
