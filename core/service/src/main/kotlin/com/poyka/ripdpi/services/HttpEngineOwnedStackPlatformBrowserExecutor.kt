package com.poyka.ripdpi.services

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsCallTimeoutMs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val OwnedStackBrowserConnectTimeoutMs = 20_000
private const val OwnedStackBrowserReadTimeoutMs = 30_000

// Same body cap and overall deadline as the native owned-TLS path.
private const val OwnedStackBrowserMaxBodyBytes = 8L * 1024 * 1024

@Singleton
class HttpEngineOwnedStackPlatformBrowserExecutor
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : OwnedStackPlatformBrowserExecutor {
        private val applicationContext = context.applicationContext
        private val quicEnabledEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            buildHttpEngine(quicEnabled = true)
        }
        private val h2OnlyEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            buildHttpEngine(quicEnabled = false)
        }

        @SuppressLint("NewApi")
        private fun buildHttpEngine(quicEnabled: Boolean): HttpEngine =
            HttpEngine
                .Builder(applicationContext)
                .setEnableHttp2(true)
                .setEnableQuic(quicEnabled)
                .setEnableBrotli(true)
                .build()

        @SuppressLint("NewApi")
        override suspend fun execute(request: OwnedStackPlatformRequest): OwnedStackPlatformResponse =
            withContext(Dispatchers.IO) {
                val deadlineNanos =
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DefaultNativeOwnedTlsCallTimeoutMs)
                val engine = if (request.quicEnabled) quicEnabledEngine else h2OnlyEngine
                val connection =
                    engine.openConnection(URL(request.url)) as? HttpURLConnection
                        ?: throw IOException("HttpEngine returned a non-HTTP connection")
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = OwnedStackBrowserConnectTimeoutMs
                    connection.readTimeout = OwnedStackBrowserReadTimeoutMs
                    connection.requestMethod = request.method.uppercase()
                    request.headers.forEach(connection::setRequestProperty)
                    val statusCode = connection.responseCode
                    val bodyStream =
                        when {
                            statusCode >= HttpURLConnection.HTTP_BAD_REQUEST -> connection.errorStream
                            else -> connection.inputStream
                        }
                    OwnedStackPlatformResponse(
                        finalUrl = connection.url?.toString() ?: request.url,
                        statusCode = statusCode,
                        body =
                            bodyStream?.use {
                                readBoundedBytes(it, OwnedStackBrowserMaxBodyBytes, deadlineNanos)
                            } ?: ByteArray(0),
                        contentType = connection.contentType,
                    )
                } finally {
                    connection.disconnect()
                }
            }
    }
