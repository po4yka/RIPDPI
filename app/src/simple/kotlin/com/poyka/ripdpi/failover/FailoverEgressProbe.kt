package com.poyka.ripdpi.failover

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
import javax.inject.Inject
import kotlin.coroutines.resume

internal fun interface FailoverEgressProbe {
    suspend fun probe(endpoint: FailoverProxyEndpoint): Boolean
}

internal data class FailoverProxyEndpoint(
    val host: String,
    val port: Int,
)

internal class HttpFailoverEgressProbe
    @Inject
    constructor() : FailoverEgressProbe {
        private val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(ProbeTimeoutSeconds, TimeUnit.SECONDS)
                .build()

        override suspend fun probe(endpoint: FailoverProxyEndpoint): Boolean =
            suspendCancellableCoroutine { continuation ->
                val call =
                    client
                        .newBuilder()
                        .proxy(
                            Proxy(
                                Proxy.Type.SOCKS,
                                InetSocketAddress.createUnresolved(endpoint.host, endpoint.port),
                            ),
                        ).build()
                        .newCall(
                            Request
                                .Builder()
                                .url(ProbeUrl)
                                .get()
                                .build(),
                        )
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            response.use {
                                if (continuation.isActive) {
                                    continuation.resume(response.code in SuccessfulStatusRange)
                                }
                            }
                        }
                    },
                )
            }

        private companion object {
            const val ProbeUrl = "https://connectivitycheck.gstatic.com/generate_204"
            const val ProbeTimeoutSeconds = 10L
            val SuccessfulStatusRange = 200..299
        }
    }
