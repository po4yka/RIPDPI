package com.poyka.ripdpi.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class NativeOwnedTlsHttpResult(
    val statusCode: Int,
    val body: ByteArray,
    val finalUrl: String? = null,
)

data class NativeOwnedTlsHttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val tlsProfileId: String,
    val connectTimeoutMs: Long = DefaultNativeOwnedTlsConnectTimeoutMs,
    val readTimeoutMs: Long = DefaultNativeOwnedTlsReadTimeoutMs,
    val callTimeoutMs: Long = DefaultNativeOwnedTlsCallTimeoutMs,
    val maxRedirects: Int = DefaultNativeOwnedTlsMaxRedirects,
)

interface NativeOwnedTlsHttpFetcher {
    suspend fun execute(request: NativeOwnedTlsHttpRequest): NativeOwnedTlsHttpResult
}

interface NativeOwnedTlsHttpFetcherBindings {
    fun execute(requestJson: String): String?
}

class NativeOwnedTlsHttpFetcherNativeBindings
    @Inject
    constructor() : NativeOwnedTlsHttpFetcherBindings {
        override fun execute(requestJson: String): String? {
            RipDpiNativeLoader.ensureLoaded()
            return jniExecute(requestJson)
        }

        private external fun jniExecute(requestJson: String): String?
    }

@Singleton
class DefaultNativeOwnedTlsHttpFetcher
    @Inject
    constructor(
        private val bindings: NativeOwnedTlsHttpFetcherBindings,
    ) : NativeOwnedTlsHttpFetcher {
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun execute(request: NativeOwnedTlsHttpRequest): NativeOwnedTlsHttpResult =
            withContext(Dispatchers.IO) {
                val payload =
                    bindings.execute(json.encodeToString(request.toNative()))
                        ?: throw IOException("native TLS fetch bridge returned no response")
                val response =
                    try {
                        json.decodeFromString(NativeOwnedTlsHttpResponse.serializer(), payload)
                    } catch (error: Exception) {
                        throw IOException("native TLS fetch bridge returned malformed response", error)
                    }
                response.error?.takeIf(String::isNotBlank)?.let { message ->
                    throw IOException(message)
                }
                val statusCode = response.statusCode ?: throw IOException("native TLS fetch bridge missing status code")
                val body =
                    response.bodyBase64?.let { encoded ->
                        try {
                            Base64.getDecoder().decode(encoded)
                        } catch (error: IllegalArgumentException) {
                            throw IOException("native TLS fetch bridge returned invalid body encoding", error)
                        }
                    } ?: ByteArray(0)
                NativeOwnedTlsHttpResult(
                    statusCode = statusCode,
                    body = body,
                    finalUrl = response.finalUrl,
                )
            }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NativeOwnedTlsHttpFetcherModule {
    @Binds
    @Singleton
    abstract fun bindNativeOwnedTlsHttpFetcher(fetcher: DefaultNativeOwnedTlsHttpFetcher): NativeOwnedTlsHttpFetcher

    @Binds
    @Singleton
    abstract fun bindNativeOwnedTlsHttpFetcherBindings(
        bindings: NativeOwnedTlsHttpFetcherNativeBindings,
    ): NativeOwnedTlsHttpFetcherBindings
}

@Serializable
private data class NativeOwnedTlsHttpRequestPayload(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    @SerialName("tlsProfileId")
    val tlsProfileId: String,
    @SerialName("connectTimeoutMs")
    val connectTimeoutMs: Long,
    @SerialName("readTimeoutMs")
    val readTimeoutMs: Long,
    @SerialName("callTimeoutMs")
    val callTimeoutMs: Long,
    @SerialName("maxRedirects")
    val maxRedirects: Int,
)

@Serializable
private data class NativeOwnedTlsHttpResponse(
    @SerialName("statusCode")
    val statusCode: Int? = null,
    @SerialName("bodyBase64")
    val bodyBase64: String? = null,
    @SerialName("finalUrl")
    val finalUrl: String? = null,
    val error: String? = null,
)

private fun NativeOwnedTlsHttpRequest.toNative() =
    NativeOwnedTlsHttpRequestPayload(
        method = method,
        url = url,
        headers = headers,
        tlsProfileId = tlsProfileId,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        callTimeoutMs = callTimeoutMs,
        maxRedirects = maxRedirects,
    )

const val DefaultNativeOwnedTlsConnectTimeoutMs = 20_000L
const val DefaultNativeOwnedTlsReadTimeoutMs = 90_000L
const val DefaultNativeOwnedTlsCallTimeoutMs = 120_000L
const val DefaultNativeOwnedTlsMaxRedirects = 5
