package com.poyka.ripdpi.services

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

fun ownedStackBrowserLaunchUrl(authority: String?): String? {
    val normalizedAuthority = authority?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
    return "https://$normalizedAuthority/"
}
