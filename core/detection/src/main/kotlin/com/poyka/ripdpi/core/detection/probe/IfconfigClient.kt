package com.poyka.ripdpi.core.detection.probe

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object IfconfigClient {
    private const val ENDPOINT = "https://ifconfig.me/ip"
    private const val USER_AGENT = "RIPDPI/1.0 (Android)"

    suspend fun fetchDirectIp(
        dispatchers: AppCoroutineDispatchers,
        timeoutMs: Int = 7000,
    ): Result<String> = fetchIp(dispatchers = dispatchers, timeoutMs = timeoutMs)

    suspend fun fetchIpViaProxy(
        dispatchers: AppCoroutineDispatchers,
        endpoint: ProxyEndpoint,
        timeoutMs: Int = 7000,
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchIp(
        dispatchers: AppCoroutineDispatchers,
        timeoutMs: Int,
        proxy: Proxy? = null,
    ): Result<String> =
        withContext(dispatchers.io) {
            val url = URL(ENDPOINT)
            val connection = if (proxy == null) url.openConnection() else url.openConnection(proxy)
            val https =
                connection as? HttpsURLConnection
                    ?: return@withContext Result.failure(IllegalStateException("Not an HTTPS connection"))

            try {
                https.instanceFollowRedirects = true
                https.requestMethod = "GET"
                https.useCaches = false
                https.connectTimeout = timeoutMs
                https.readTimeout = timeoutMs
                https.setRequestProperty("User-Agent", USER_AGENT)
                https.setRequestProperty("Accept", "text/plain")

                val code = https.responseCode
                if (code !in 200..299) {
                    val errorText =
                        https.errorStream
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?.trim()
                    return@withContext Result.failure(
                        IOException(
                            buildString {
                                append("HTTP ")
                                append(code)
                                if (!errorText.isNullOrBlank()) {
                                    append(": ")
                                    append(errorText)
                                }
                            },
                        ),
                    )
                }

                val body =
                    https.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                        .trim()
                if (body.isBlank()) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }
                Result.success(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                https.disconnect()
            }
        }
}
