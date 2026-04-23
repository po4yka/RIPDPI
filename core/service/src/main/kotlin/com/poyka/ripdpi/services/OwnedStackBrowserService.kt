package com.poyka.ripdpi.services

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsCallTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsConnectTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsMaxRedirects
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsReadTimeoutMs
import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val OwnedStackHttpEngineMinSdk = 34
private const val OwnedStackAndroid17ApiLevel = 37
private const val OwnedStackBrowserConnectTimeoutMs = 20_000
private const val OwnedStackBrowserReadTimeoutMs = 30_000
private const val OwnedStackBrowserUserAgent = "RIPDPI owned-stack browser"

enum class OwnedStackBrowserBackend {
    HTTP_ENGINE,
    NATIVE_OWNED_TLS,
}

data class OwnedStackBrowserSupport(
    val platformHttpEngineAvailable: Boolean,
    val android17EchEligible: Boolean,
)

data class OwnedStackBrowserPage(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val bodyText: String,
    val contentType: String?,
    val backend: OwnedStackBrowserBackend,
    val android17EchEligible: Boolean,
    val tlsProfileId: String? = null,
)

interface OwnedStackBrowserSupportProvider {
    fun current(): OwnedStackBrowserSupport
}

interface OwnedStackPlatformBrowserExecutor {
    suspend fun execute(url: String): OwnedStackPlatformResponse
}

interface OwnedStackBrowserService {
    fun currentSupport(): OwnedStackBrowserSupport

    fun normalizeUrl(rawUrl: String): String

    suspend fun fetch(rawUrl: String): OwnedStackBrowserPage
}

data class OwnedStackPlatformResponse(
    val finalUrl: String,
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String?,
)

@Singleton
class BuildVersionOwnedStackBrowserSupportProvider
    @Inject
    constructor() : OwnedStackBrowserSupportProvider {
        override fun current(): OwnedStackBrowserSupport =
            OwnedStackBrowserSupport(
                platformHttpEngineAvailable = Build.VERSION.SDK_INT >= OwnedStackHttpEngineMinSdk,
                android17EchEligible = Build.VERSION.SDK_INT >= OwnedStackAndroid17ApiLevel,
            )
    }

@Singleton
class HttpEngineOwnedStackPlatformBrowserExecutor
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : OwnedStackPlatformBrowserExecutor {
        private val applicationContext = context.applicationContext
        private val engine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HttpEngine
                .Builder(applicationContext)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .setEnableBrotli(true)
                .build()
        }

        override suspend fun execute(url: String): OwnedStackPlatformResponse =
            withContext(Dispatchers.IO) {
                val connection =
                    engine.openConnection(URL(url)) as? HttpURLConnection
                        ?: throw IOException("HttpEngine returned a non-HTTP connection")
                try {
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = OwnedStackBrowserConnectTimeoutMs
                    connection.readTimeout = OwnedStackBrowserReadTimeoutMs
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", OwnedStackBrowserUserAgent)
                    val statusCode = connection.responseCode
                    val bodyStream =
                        when {
                            statusCode >= HttpURLConnection.HTTP_BAD_REQUEST -> connection.errorStream
                            else -> connection.inputStream
                        }
                    OwnedStackPlatformResponse(
                        finalUrl = connection.url?.toString() ?: url,
                        statusCode = statusCode,
                        body = bodyStream?.use { it.readBytes() } ?: ByteArray(0),
                        contentType = connection.contentType,
                    )
                } finally {
                    connection.disconnect()
                }
            }
    }

@Singleton
class DefaultOwnedStackBrowserService
    @Inject
    constructor(
        private val supportProvider: OwnedStackBrowserSupportProvider,
        private val platformExecutorProvider: Provider<OwnedStackPlatformBrowserExecutor>,
        private val nativeOwnedTlsHttpFetcher: NativeOwnedTlsHttpFetcher,
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) : OwnedStackBrowserService {
        override fun currentSupport(): OwnedStackBrowserSupport = supportProvider.current()

        override fun normalizeUrl(rawUrl: String): String = normalizeOwnedStackBrowserUrl(rawUrl)

        override suspend fun fetch(rawUrl: String): OwnedStackBrowserPage {
            val requestedUrl = normalizeUrl(rawUrl)
            val support = currentSupport()
            if (support.platformHttpEngineAvailable) {
                try {
                    val response = platformExecutorProvider.get().execute(requestedUrl)
                    return response.toOwnedStackBrowserPage(
                        requestedUrl = requestedUrl,
                        backend = OwnedStackBrowserBackend.HTTP_ENGINE,
                        android17EchEligible = support.android17EchEligible,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Fall through to the native owned-TLS bridge so the browser shell still works
                    // on older devices and on platform-stack failures.
                }
            }

            val authority = requestedUrl.authorityFromUrl()
            val selection = tlsClientFactory.selectionForAuthority(authority)
            val response =
                nativeOwnedTlsHttpFetcher.execute(
                    NativeOwnedTlsHttpRequest(
                        url = requestedUrl,
                        headers = mapOf("User-Agent" to OwnedStackBrowserUserAgent),
                        tlsProfileId = selection.profileId,
                        connectTimeoutMs = DefaultNativeOwnedTlsConnectTimeoutMs,
                        readTimeoutMs = DefaultNativeOwnedTlsReadTimeoutMs,
                        callTimeoutMs = DefaultNativeOwnedTlsCallTimeoutMs,
                        maxRedirects = DefaultNativeOwnedTlsMaxRedirects,
                    ),
                )
            return OwnedStackPlatformResponse(
                finalUrl = response.finalUrl ?: requestedUrl,
                statusCode = response.statusCode,
                body = response.body,
                contentType = null,
            ).toOwnedStackBrowserPage(
                requestedUrl = requestedUrl,
                backend = OwnedStackBrowserBackend.NATIVE_OWNED_TLS,
                android17EchEligible = false,
                tlsProfileId = selection.profileId,
            )
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OwnedStackBrowserServiceModule {
    @Binds
    @Singleton
    abstract fun bindOwnedStackBrowserSupportProvider(
        provider: BuildVersionOwnedStackBrowserSupportProvider,
    ): OwnedStackBrowserSupportProvider

    @Binds
    @Singleton
    abstract fun bindOwnedStackPlatformBrowserExecutor(
        executor: HttpEngineOwnedStackPlatformBrowserExecutor,
    ): OwnedStackPlatformBrowserExecutor

    @Binds
    @Singleton
    abstract fun bindOwnedStackBrowserService(service: DefaultOwnedStackBrowserService): OwnedStackBrowserService
}

fun ownedStackBrowserLaunchUrl(authority: String?): String? {
    val normalizedAuthority = authority?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
    return "https://$normalizedAuthority/"
}

private fun normalizeOwnedStackBrowserUrl(rawUrl: String): String {
    val candidate = rawUrl.trim()
    require(candidate.isNotBlank()) { "Enter a URL to open in the RIPDPI browser." }
    val withScheme =
        if ("://" in candidate) {
            candidate
        } else {
            "https://$candidate"
        }
    val parsed = URI(withScheme)
    require(parsed.scheme.equals("https", ignoreCase = true)) {
        "Only HTTPS URLs are supported in the RIPDPI browser."
    }
    require(!parsed.host.isNullOrBlank()) { "Enter a valid HTTPS host." }
    return parsed.toString()
}

private fun String.authorityFromUrl(): String? = runCatching { URI(this).host }.getOrNull()

private fun OwnedStackPlatformResponse.toOwnedStackBrowserPage(
    requestedUrl: String,
    backend: OwnedStackBrowserBackend,
    android17EchEligible: Boolean,
    tlsProfileId: String? = null,
): OwnedStackBrowserPage =
    OwnedStackBrowserPage(
        requestedUrl = requestedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        bodyText = decodeOwnedStackBody(body, contentType),
        contentType = contentType,
        backend = backend,
        android17EchEligible = android17EchEligible,
        tlsProfileId = tlsProfileId,
    )

private fun decodeOwnedStackBody(
    body: ByteArray,
    contentType: String?,
): String {
    if (body.isEmpty()) {
        return ""
    }
    val normalizedContentType = contentType?.lowercase().orEmpty()
    val isTextual =
        normalizedContentType.isBlank() ||
            normalizedContentType.startsWith("text/") ||
            normalizedContentType.contains("json") ||
            normalizedContentType.contains("xml") ||
            normalizedContentType.contains("javascript")
    if (!isTextual) {
        return "Binary response (${body.size} bytes)."
    }
    val charset = contentType.charsetFromContentType()
    return body.toString(charset)
}

private fun String?.charsetFromContentType(): Charset {
    val charsetName =
        this
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf(String::isNotBlank)
    return charsetName
        ?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: StandardCharsets.UTF_8
}
