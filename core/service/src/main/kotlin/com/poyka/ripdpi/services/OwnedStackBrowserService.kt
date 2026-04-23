package com.poyka.ripdpi.services

import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsCallTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsConnectTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsMaxRedirects
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsReadTimeoutMs
import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModePolicyTtlMs
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.effectiveTransportPolicyEnvelope
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

private val ownedStackLog = Logger.withTag("OwnedStack")
private val knownAndroid17EchDomains =
    listOf(
        OwnedStackEchDomainRule(domain = "raw.githubusercontent.com", includeSubdomains = true),
        OwnedStackEchDomainRule(domain = "connectivitycheck.gstatic.com", includeSubdomains = false),
    )

enum class OwnedStackBrowserBackend {
    HTTP_ENGINE,
    NATIVE_OWNED_TLS,
}

enum class SecureHttpMode {
    OWNED_STACK,
}

enum class SecureHttpDnsPolicy {
    SYSTEM_DEFAULT,
    CAPABILITY_SCOPED,
}

enum class SecureHttpEchMode {
    OPPORTUNISTIC,
    REQUIRE_CONFIRMED,
}

enum class SecureHttpQuicPolicy {
    AUTO,
    H2_ONLY,
}

enum class OwnedStackNativeFallbackReason {
    PLATFORM_UNAVAILABLE,
    ECH_CONFIRMATION_MISSING,
    PLATFORM_FAILURE,
}

data class OwnedStackBrowserSupport(
    val platformHttpEngineAvailable: Boolean,
    val android17EchEligible: Boolean,
)

data class OwnedStackExecutionTrace(
    val authority: String? = null,
    val confirmedEchCapableAuthority: Boolean = false,
    val echEnforcedDomain: Boolean = false,
    val effectiveEchMode: SecureHttpEchMode = SecureHttpEchMode.OPPORTUNISTIC,
    val platformAttempted: Boolean = false,
    val h2RetryTriggered: Boolean = false,
    val finalQuicPolicy: SecureHttpQuicPolicy = SecureHttpQuicPolicy.AUTO,
    val nativeFallbackReason: OwnedStackNativeFallbackReason? = null,
)

data class SecureHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val mode: SecureHttpMode = SecureHttpMode.OWNED_STACK,
    val dnsPolicy: SecureHttpDnsPolicy = SecureHttpDnsPolicy.CAPABILITY_SCOPED,
    val echMode: SecureHttpEchMode = SecureHttpEchMode.OPPORTUNISTIC,
    val quicPolicy: SecureHttpQuicPolicy = SecureHttpQuicPolicy.AUTO,
)

data class SecureHttpResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String?,
    val backend: OwnedStackBrowserBackend,
    val android17EchEligible: Boolean,
    val tlsProfileId: String? = null,
    val executionTrace: OwnedStackExecutionTrace = OwnedStackExecutionTrace(),
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
    val executionTrace: OwnedStackExecutionTrace = OwnedStackExecutionTrace(),
)

data class OwnedStackPlatformResponse(
    val finalUrl: String,
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String?,
)

data class OwnedStackPlatformRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val quicEnabled: Boolean,
)

private data class OwnedStackAuthorityEvidence(
    val confirmedEchCapable: Boolean = false,
    val echEnforcedDomain: Boolean = false,
)

private data class OwnedStackEchDomainRule(
    val domain: String,
    val includeSubdomains: Boolean,
)

interface OwnedStackBrowserSupportProvider {
    fun current(): OwnedStackBrowserSupport
}

interface OwnedStackPlatformBrowserExecutor {
    suspend fun execute(request: OwnedStackPlatformRequest): OwnedStackPlatformResponse
}

interface SecureHttpClient {
    fun currentSupport(): OwnedStackBrowserSupport

    fun normalizeUrl(rawUrl: String): String

    suspend fun execute(request: SecureHttpRequest): SecureHttpResponse
}

interface OwnedStackBrowserService {
    fun currentSupport(): OwnedStackBrowserSupport

    fun normalizeUrl(rawUrl: String): String

    suspend fun fetch(rawUrl: String): OwnedStackBrowserPage
}

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
        private val quicEnabledEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HttpEngine
                .Builder(applicationContext)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .setEnableBrotli(true)
                .build()
        }
        private val h2OnlyEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            HttpEngine
                .Builder(applicationContext)
                .setEnableHttp2(true)
                .setEnableQuic(false)
                .setEnableBrotli(true)
                .build()
        }

        override suspend fun execute(request: OwnedStackPlatformRequest): OwnedStackPlatformResponse =
            withContext(Dispatchers.IO) {
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
                        body = bodyStream?.use { it.readBytes() } ?: ByteArray(0),
                        contentType = connection.contentType,
                    )
                } finally {
                    connection.disconnect()
                }
            }
    }

@Singleton
class DefaultSecureHttpClient
    @Inject
    constructor(
        private val supportProvider: OwnedStackBrowserSupportProvider,
        private val platformExecutorProvider: Provider<OwnedStackPlatformBrowserExecutor>,
        private val nativeOwnedTlsHttpFetcher: NativeOwnedTlsHttpFetcher,
        private val tlsClientFactory: OwnedTlsClientFactory,
        private val networkFingerprintProvider: NetworkFingerprintProvider,
        private val serverCapabilityStore: ServerCapabilityStore,
    ) : SecureHttpClient {
        override fun currentSupport(): OwnedStackBrowserSupport = supportProvider.current()

        override fun normalizeUrl(rawUrl: String): String = normalizeOwnedStackBrowserUrl(rawUrl)

        override suspend fun execute(request: SecureHttpRequest): SecureHttpResponse {
            require(request.mode == SecureHttpMode.OWNED_STACK) {
                "SecureHttpClient currently supports only the owned-stack mode."
            }
            require(request.method.equals("GET", ignoreCase = true)) {
                "SecureHttpClient currently supports only GET requests."
            }

            val requestedUrl = normalizeUrl(request.url)
            val support = currentSupport()
            val authority = requestedUrl.authorityFromUrl()
            val authorityEvidence = resolveAuthorityEvidence(authority, request.dnsPolicy)
            val confirmedPlatformEch =
                support.android17EchEligible &&
                    (authorityEvidence.confirmedEchCapable || authorityEvidence.echEnforcedDomain)
            val effectiveEchMode =
                if (request.echMode == SecureHttpEchMode.REQUIRE_CONFIRMED && confirmedPlatformEch) {
                    SecureHttpEchMode.REQUIRE_CONFIRMED
                } else {
                    SecureHttpEchMode.OPPORTUNISTIC
                }
            val defaultTrace =
                OwnedStackExecutionTrace(
                    authority = authority,
                    confirmedEchCapableAuthority = authorityEvidence.confirmedEchCapable,
                    echEnforcedDomain = authorityEvidence.echEnforcedDomain,
                    effectiveEchMode = effectiveEchMode,
                    finalQuicPolicy = request.quicPolicy,
                )
            val headers = request.headers.withDefaultUserAgent()

            val canAttemptPlatform =
                support.platformHttpEngineAvailable &&
                    (request.echMode != SecureHttpEchMode.REQUIRE_CONFIRMED || confirmedPlatformEch)

            if (canAttemptPlatform) {
                try {
                    return executePlatformRequest(
                        requestedUrl = requestedUrl,
                        support = support,
                        request = request,
                        headers = headers,
                        quicEnabled = request.quicPolicy != SecureHttpQuicPolicy.H2_ONLY,
                        trace = defaultTrace.copy(platformAttempted = true),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (request.quicPolicy == SecureHttpQuicPolicy.AUTO) {
                        ownedStackLog.w(error) {
                            "Owned-stack platform request failed for ${authority.orEmpty()}; retrying with H2-only platform stack"
                        }
                        try {
                            return executePlatformRequest(
                                requestedUrl = requestedUrl,
                                support = support,
                                request = request,
                                headers = headers,
                                quicEnabled = false,
                                trace =
                                    defaultTrace.copy(
                                        platformAttempted = true,
                                        h2RetryTriggered = true,
                                        finalQuicPolicy = SecureHttpQuicPolicy.H2_ONLY,
                                    ),
                            )
                        } catch (retryError: CancellationException) {
                            throw retryError
                        } catch (retryError: Throwable) {
                            ownedStackLog.w(retryError) {
                                "Owned-stack H2-only retry failed for ${authority.orEmpty()}; falling back to native owned TLS"
                            }
                            return executeNativeRequest(
                                requestedUrl = requestedUrl,
                                support = support,
                                headers = headers,
                                trace =
                                    defaultTrace.copy(
                                        platformAttempted = true,
                                        h2RetryTriggered = true,
                                        finalQuicPolicy = SecureHttpQuicPolicy.H2_ONLY,
                                        nativeFallbackReason = OwnedStackNativeFallbackReason.PLATFORM_FAILURE,
                                    ),
                            )
                        }
                    }

                    ownedStackLog.w(error) {
                        "Owned-stack platform stack failed for ${authority.orEmpty()}; falling back to native owned TLS"
                    }
                    return executeNativeRequest(
                        requestedUrl = requestedUrl,
                        support = support,
                        headers = headers,
                        trace =
                            defaultTrace.copy(
                                platformAttempted = true,
                                nativeFallbackReason = OwnedStackNativeFallbackReason.PLATFORM_FAILURE,
                            ),
                    )
                }
            }

            val fallbackReason =
                if (support.platformHttpEngineAvailable) {
                    OwnedStackNativeFallbackReason.ECH_CONFIRMATION_MISSING
                } else {
                    OwnedStackNativeFallbackReason.PLATFORM_UNAVAILABLE
                }
            if (fallbackReason == OwnedStackNativeFallbackReason.ECH_CONFIRMATION_MISSING) {
                ownedStackLog.i {
                    "Owned-stack request for ${authority.orEmpty()} requires confirmed Android 17 ECH; using native owned TLS because no fresh ECH-capable authority evidence is cached"
                }
            }
            return executeNativeRequest(
                requestedUrl = requestedUrl,
                support = support,
                headers = headers,
                trace = defaultTrace.copy(nativeFallbackReason = fallbackReason),
            )
        }

        private suspend fun executePlatformRequest(
            requestedUrl: String,
            support: OwnedStackBrowserSupport,
            request: SecureHttpRequest,
            headers: Map<String, String>,
            quicEnabled: Boolean,
            trace: OwnedStackExecutionTrace,
        ): SecureHttpResponse =
            platformExecutorProvider
                .get()
                .execute(
                    OwnedStackPlatformRequest(
                        method = request.method,
                        url = requestedUrl,
                        headers = headers,
                        quicEnabled = quicEnabled,
                    ),
                ).toSecureHttpResponse(
                    requestedUrl = requestedUrl,
                    android17EchEligible = support.android17EchEligible,
                    executionTrace =
                        trace.copy(
                            finalQuicPolicy =
                                if (quicEnabled) {
                                    request.quicPolicy
                                } else {
                                    SecureHttpQuicPolicy.H2_ONLY
                                },
                        ),
                )

        private suspend fun executeNativeRequest(
            requestedUrl: String,
            support: OwnedStackBrowserSupport,
            headers: Map<String, String>,
            trace: OwnedStackExecutionTrace,
        ): SecureHttpResponse {
            val authority = requestedUrl.authorityFromUrl()
            val selection = tlsClientFactory.selectionForAuthority(authority)
            val response =
                nativeOwnedTlsHttpFetcher.execute(
                    NativeOwnedTlsHttpRequest(
                        url = requestedUrl,
                        headers = headers,
                        tlsProfileId = selection.profileId,
                        connectTimeoutMs = DefaultNativeOwnedTlsConnectTimeoutMs,
                        readTimeoutMs = DefaultNativeOwnedTlsReadTimeoutMs,
                        callTimeoutMs = DefaultNativeOwnedTlsCallTimeoutMs,
                        maxRedirects = DefaultNativeOwnedTlsMaxRedirects,
                    ),
                )
            return SecureHttpResponse(
                requestedUrl = requestedUrl,
                finalUrl = response.finalUrl ?: requestedUrl,
                statusCode = response.statusCode,
                body = response.body,
                contentType = null,
                backend = OwnedStackBrowserBackend.NATIVE_OWNED_TLS,
                android17EchEligible = support.android17EchEligible,
                tlsProfileId = selection.profileId,
                executionTrace = trace,
            )
        }

        private suspend fun resolveAuthorityEvidence(
            authority: String?,
            dnsPolicy: SecureHttpDnsPolicy,
        ): OwnedStackAuthorityEvidence {
            val normalizedHost = authority?.normalizeOwnedStackHost() ?: return OwnedStackAuthorityEvidence()
            val echEnforcedDomain = normalizedHost.matchesKnownAndroid17EchDomain()
            if (dnsPolicy != SecureHttpDnsPolicy.CAPABILITY_SCOPED) {
                return OwnedStackAuthorityEvidence(echEnforcedDomain = echEnforcedDomain)
            }
            val fingerprintHash =
                networkFingerprintProvider.capture()?.scopeKey()
                    ?: return OwnedStackAuthorityEvidence(echEnforcedDomain = echEnforcedDomain)
            val now = System.currentTimeMillis()
            val confirmed =
                serverCapabilityStore
                    .directPathCapabilitiesForFingerprint(fingerprintHash)
                    .firstOrNull { record ->
                        record.authority.normalizeOwnedStackHost() == normalizedHost &&
                            record.hasFreshOwnedStackDnsEvidence(now)
                    }?.effectiveTransportPolicyEnvelope()
                    ?.dnsClassification == DirectDnsClassification.ECH_CAPABLE
            return OwnedStackAuthorityEvidence(
                confirmedEchCapable = confirmed,
                echEnforcedDomain = echEnforcedDomain,
            )
        }
    }

@Singleton
class DefaultOwnedStackBrowserService
    @Inject
    constructor(
        private val secureHttpClient: SecureHttpClient,
    ) : OwnedStackBrowserService {
        override fun currentSupport(): OwnedStackBrowserSupport = secureHttpClient.currentSupport()

        override fun normalizeUrl(rawUrl: String): String = secureHttpClient.normalizeUrl(rawUrl)

        override suspend fun fetch(rawUrl: String): OwnedStackBrowserPage =
            secureHttpClient.execute(SecureHttpRequest(url = normalizeUrl(rawUrl))).toOwnedStackBrowserPage()
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
    abstract fun bindSecureHttpClient(client: DefaultSecureHttpClient): SecureHttpClient

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

private fun SecureHttpResponse.toOwnedStackBrowserPage(): OwnedStackBrowserPage =
    OwnedStackBrowserPage(
        requestedUrl = requestedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        bodyText = decodeOwnedStackBody(body, contentType),
        contentType = contentType,
        backend = backend,
        android17EchEligible = android17EchEligible,
        tlsProfileId = tlsProfileId,
        executionTrace = executionTrace,
    )

private fun OwnedStackPlatformResponse.toSecureHttpResponse(
    requestedUrl: String,
    android17EchEligible: Boolean,
    executionTrace: OwnedStackExecutionTrace,
): SecureHttpResponse =
    SecureHttpResponse(
        requestedUrl = requestedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        body = body,
        contentType = contentType,
        backend = OwnedStackBrowserBackend.HTTP_ENGINE,
        android17EchEligible = android17EchEligible,
        executionTrace = executionTrace,
    )

private fun Map<String, String>.withDefaultUserAgent(): Map<String, String> =
    if (keys.any { it.equals("User-Agent", ignoreCase = true) }) {
        this
    } else {
        this + ("User-Agent" to OwnedStackBrowserUserAgent)
    }

private fun String.normalizeOwnedStackHost(): String = substringBefore(':').trim().lowercase()

private fun String.matchesKnownAndroid17EchDomain(): Boolean =
    knownAndroid17EchDomains.any { rule ->
        if (rule.includeSubdomains) {
            this == rule.domain || this.endsWith(".${rule.domain}")
        } else {
            this == rule.domain
        }
    }

private fun ServerCapabilityRecord.hasFreshOwnedStackDnsEvidence(nowMillis: Long): Boolean =
    updatedAt > 0L && nowMillis - updatedAt <= DirectModePolicyTtlMs

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
