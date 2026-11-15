package com.poyka.ripdpi.core

import com.poyka.ripdpi.serialization.RipDpiJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NativeOwnedTlsHttpResult(
    val statusCode: Int,
    val body: ByteArray,
    val finalUrl: String? = null,
    val tlsProfileId: String? = null,
    val tlsProfileCatalogVersion: String? = null,
    val tlsJa3ParityTarget: String? = null,
    val tlsJa4ParityTarget: String? = null,
    val tlsBrowserFamily: String? = null,
    val tlsBrowserTrack: String? = null,
    val tlsTemplateAlpn: String? = null,
    val tlsTemplateExtensionOrderFamily: String? = null,
    val tlsTemplateGreaseStyle: String? = null,
    val tlsTemplateSupportedGroupsProfile: String? = null,
    val tlsTemplateKeyShareProfile: String? = null,
    val tlsTemplateRecordChoreography: String? = null,
    val tlsTemplateEchCapable: Boolean? = null,
    val tlsTemplateEchBootstrapPolicy: String? = null,
    val tlsTemplateEchBootstrapResolverId: String? = null,
    val tlsTemplateEchOuterExtensionPolicy: String? = null,
    val clientHelloSizeHint: Int? = null,
    val clientHelloInvariantStatus: String? = null,
    val contentType: String? = null,
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
    fun createRequest(): Long

    fun execute(
        requestHandle: Long,
        requestJson: String,
    ): String

    fun cancelRequest(requestHandle: Long)

    fun releaseRequest(requestHandle: Long)
}

class NativeOwnedTlsHttpFetcherNativeBindings
    @Inject
    constructor() : NativeOwnedTlsHttpFetcherBindings {
        override fun createRequest(): Long {
            val payload =
                dispatch(NativeOwnedTlsHttpCommand(operation = "create"))
                    ?: throw IOException("native TLS fetch bridge returned no request handle")
            return RipDpiJson.decodeFromString(NativeOwnedTlsHttpReservation.serializer(), payload).requestHandle
        }

        override fun execute(
            requestHandle: Long,
            requestJson: String,
        ): String =
            dispatch(NativeOwnedTlsHttpCommand("execute", requestHandle, requestJson))
                ?: throw IOException("native TLS fetch bridge returned no response")

        override fun cancelRequest(requestHandle: Long) {
            dispatch(NativeOwnedTlsHttpCommand("cancel", requestHandle))
        }

        override fun releaseRequest(requestHandle: Long) {
            dispatch(NativeOwnedTlsHttpCommand("release", requestHandle))
        }

        private fun dispatch(command: NativeOwnedTlsHttpCommand): String? {
            RipDpiNativeLoader.ensureLoaded()
            return jniExecute(RipDpiJson.encodeToString(command))
        }

        private external fun jniExecute(requestJson: String): String?
    }

@Singleton
class DefaultNativeOwnedTlsHttpFetcher
    @Inject
    constructor(
        private val bindings: NativeOwnedTlsHttpFetcherBindings,
    ) : NativeOwnedTlsHttpFetcher {
        private val json = RipDpiJson

        override suspend fun execute(request: NativeOwnedTlsHttpRequest): NativeOwnedTlsHttpResult =
            withContext(Dispatchers.IO) {
                val payload = exchange(json.encodeToString(request.toNative()))
                val response =
                    try {
                        json.decodeFromString(NativeOwnedTlsHttpResponse.serializer(), payload)
                    } catch (error: SerializationException) {
                        throw IOException("native TLS fetch bridge returned malformed response", error)
                    }
                if (response.cancelled) throw CancellationException("native TLS fetch cancelled")
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
                    contentType = response.contentType,
                    tlsProfileId = response.tlsProfileId,
                    tlsProfileCatalogVersion = response.tlsProfileCatalogVersion,
                    tlsJa3ParityTarget = response.tlsJa3ParityTarget,
                    tlsJa4ParityTarget = response.tlsJa4ParityTarget,
                    tlsBrowserFamily = response.tlsBrowserFamily,
                    tlsBrowserTrack = response.tlsBrowserTrack,
                    tlsTemplateAlpn = response.tlsTemplateAlpn,
                    tlsTemplateExtensionOrderFamily = response.tlsTemplateExtensionOrderFamily,
                    tlsTemplateGreaseStyle = response.tlsTemplateGreaseStyle,
                    tlsTemplateSupportedGroupsProfile = response.tlsTemplateSupportedGroupsProfile,
                    tlsTemplateKeyShareProfile = response.tlsTemplateKeyShareProfile,
                    tlsTemplateRecordChoreography = response.tlsTemplateRecordChoreography,
                    tlsTemplateEchCapable = response.tlsTemplateEchCapable,
                    tlsTemplateEchBootstrapPolicy = response.tlsTemplateEchBootstrapPolicy,
                    tlsTemplateEchBootstrapResolverId = response.tlsTemplateEchBootstrapResolverId,
                    tlsTemplateEchOuterExtensionPolicy = response.tlsTemplateEchOuterExtensionPolicy,
                    clientHelloSizeHint = response.clientHelloSizeHint,
                    clientHelloInvariantStatus = response.clientHelloInvariantStatus,
                )
            }

        private suspend fun exchange(requestJson: String): String {
            val handle =
                bindings.createRequest().takeUnless { it == 0L }
                    ?: throw IOException("native TLS fetch request limit reached")
            try {
                return suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { bindings.cancelRequest(handle) }
                    if (continuation.isActive) {
                        runCatching {
                            continuation.resume(bindings.execute(handle, requestJson))
                        }.exceptionOrNull()?.let { error ->
                            if (error !is Exception) throw error
                            continuation.resumeWithException(error)
                        }
                    }
                }
            } finally {
                bindings.releaseRequest(handle)
            }
        }
    }

@Serializable
private data class NativeOwnedTlsHttpCommand(
    val operation: String,
    val requestHandle: Long? = null,
    val requestJson: String? = null,
)

@Serializable
private data class NativeOwnedTlsHttpReservation(
    val requestHandle: Long,
)

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
    @SerialName("tlsProfileId")
    val tlsProfileId: String? = null,
    @SerialName("tlsProfileCatalogVersion")
    val tlsProfileCatalogVersion: String? = null,
    @SerialName("tlsJa3ParityTarget")
    val tlsJa3ParityTarget: String? = null,
    @SerialName("tlsJa4ParityTarget")
    val tlsJa4ParityTarget: String? = null,
    @SerialName("tlsBrowserFamily")
    val tlsBrowserFamily: String? = null,
    @SerialName("tlsBrowserTrack")
    val tlsBrowserTrack: String? = null,
    @SerialName("tlsTemplateAlpn")
    val tlsTemplateAlpn: String? = null,
    @SerialName("tlsTemplateExtensionOrderFamily")
    val tlsTemplateExtensionOrderFamily: String? = null,
    @SerialName("tlsTemplateGreaseStyle")
    val tlsTemplateGreaseStyle: String? = null,
    @SerialName("tlsTemplateSupportedGroupsProfile")
    val tlsTemplateSupportedGroupsProfile: String? = null,
    @SerialName("tlsTemplateKeyShareProfile")
    val tlsTemplateKeyShareProfile: String? = null,
    @SerialName("tlsTemplateRecordChoreography")
    val tlsTemplateRecordChoreography: String? = null,
    @SerialName("tlsTemplateEchCapable")
    val tlsTemplateEchCapable: Boolean? = null,
    @SerialName("tlsTemplateEchBootstrapPolicy")
    val tlsTemplateEchBootstrapPolicy: String? = null,
    @SerialName("tlsTemplateEchBootstrapResolverId")
    val tlsTemplateEchBootstrapResolverId: String? = null,
    @SerialName("tlsTemplateEchOuterExtensionPolicy")
    val tlsTemplateEchOuterExtensionPolicy: String? = null,
    @SerialName("clientHelloSizeHint")
    val clientHelloSizeHint: Int? = null,
    @SerialName("clientHelloInvariantStatus")
    val clientHelloInvariantStatus: String? = null,
    val contentType: String? = null,
    val cancelled: Boolean = false,
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
