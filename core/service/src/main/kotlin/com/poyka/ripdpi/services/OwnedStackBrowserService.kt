package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsCallTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsConnectTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsMaxRedirects
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsReadTimeoutMs
import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.ServerCapabilityStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private val ownedStackLog = Logger.withTag("OwnedStack")

@Singleton
class DefaultSecureHttpClient
    @Inject
    constructor(
        private val supportProvider: OwnedStackBrowserSupportProvider,
        private val platformExecutorProvider: Provider<OwnedStackPlatformBrowserExecutor>,
        private val nativeOwnedTlsHttpFetcher: NativeOwnedTlsHttpFetcher,
        private val tlsClientFactory: OwnedTlsClientFactory,
        networkFingerprintProvider: NetworkFingerprintProvider,
        serverCapabilityStore: ServerCapabilityStore,
    ) : SecureHttpClient {
        private val echEvidenceResolver =
            OwnedStackEchEvidenceResolver(
                networkFingerprintProvider = networkFingerprintProvider,
                serverCapabilityStore = serverCapabilityStore,
            )

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
            val authority = requestedUrl.ownedStackAuthorityFromUrl()
            val authorityEvidence = echEvidenceResolver.resolve(authority, request.dnsPolicy)
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

            return if (
                support.platformHttpEngineAvailable &&
                (request.echMode != SecureHttpEchMode.REQUIRE_CONFIRMED || confirmedPlatformEch)
            ) {
                attemptPlatformWithFallback(
                    requestedUrl = requestedUrl,
                    support = support,
                    request = request,
                    headers = headers,
                    authority = authority,
                    defaultTrace = defaultTrace,
                )
            } else {
                executeNativeRequest(
                    requestedUrl = requestedUrl,
                    support = support,
                    headers = headers,
                    trace = defaultTrace.copy(nativeFallbackReason = nativeFallbackReason(support, authority)),
                )
            }
        }

        private fun nativeFallbackReason(
            support: OwnedStackBrowserSupport,
            authority: String?,
        ): OwnedStackNativeFallbackReason {
            val reason =
                if (support.platformHttpEngineAvailable) {
                    OwnedStackNativeFallbackReason.ECH_CONFIRMATION_MISSING
                } else {
                    OwnedStackNativeFallbackReason.PLATFORM_UNAVAILABLE
                }
            if (reason == OwnedStackNativeFallbackReason.ECH_CONFIRMATION_MISSING) {
                ownedStackLog.i {
                    "Owned-stack request for ${authority.orEmpty()} requires confirmed Android 17 ECH;" +
                        " using native owned TLS because no fresh ECH-capable authority evidence is cached"
                }
            }
            return reason
        }

        private suspend fun attemptPlatformWithFallback(
            requestedUrl: String,
            support: OwnedStackBrowserSupport,
            request: SecureHttpRequest,
            headers: Map<String, String>,
            authority: String?,
            defaultTrace: OwnedStackExecutionTrace,
        ): SecureHttpResponse {
            val result =
                runCatching {
                    executePlatformRequest(
                        requestedUrl = requestedUrl,
                        support = support,
                        request = request,
                        headers = headers,
                        quicEnabled = request.quicPolicy != SecureHttpQuicPolicy.H2_ONLY,
                        trace = defaultTrace.copy(platformAttempted = true),
                    )
                }
            val error = result.exceptionOrNull()
            return when {
                result.isSuccess -> {
                    result.getOrThrow()
                }

                error != null && !error.permitsOwnedStackTransportFallback() -> {
                    throw error
                }

                request.quicPolicy == SecureHttpQuicPolicy.AUTO -> {
                    attemptH2RetryWithNativeFallback(
                        requestedUrl = requestedUrl,
                        support = support,
                        request = request,
                        headers = headers,
                        authority = authority,
                        defaultTrace = defaultTrace,
                        firstError = checkNotNull(error),
                    )
                }

                else -> {
                    ownedStackLog.w(error) {
                        "Owned-stack platform stack failed for ${authority.orEmpty()}; falling back to native owned TLS"
                    }
                    executeNativeRequest(
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
        }

        private suspend fun attemptH2RetryWithNativeFallback(
            requestedUrl: String,
            support: OwnedStackBrowserSupport,
            request: SecureHttpRequest,
            headers: Map<String, String>,
            authority: String?,
            defaultTrace: OwnedStackExecutionTrace,
            firstError: Throwable,
        ): SecureHttpResponse {
            ownedStackLog.w(firstError) {
                "Owned-stack platform request failed for ${authority.orEmpty()}; retrying with H2-only platform stack"
            }
            val h2Trace =
                defaultTrace.copy(
                    platformAttempted = true,
                    h2RetryTriggered = true,
                    finalQuicPolicy = SecureHttpQuicPolicy.H2_ONLY,
                )
            val retryResult =
                runCatching {
                    executePlatformRequest(
                        requestedUrl = requestedUrl,
                        support = support,
                        request = request,
                        headers = headers,
                        quicEnabled = false,
                        trace = h2Trace,
                    )
                }
            val retryError = retryResult.exceptionOrNull()
            if (retryError != null && !retryError.permitsOwnedStackTransportFallback()) throw retryError
            return retryResult.getOrElse { error ->
                ownedStackLog.w(error) {
                    "Owned-stack H2-only retry failed for ${authority.orEmpty()}; falling back to native owned TLS"
                }
                executeNativeRequest(
                    requestedUrl = requestedUrl,
                    support = support,
                    headers = headers,
                    trace = h2Trace.copy(nativeFallbackReason = OwnedStackNativeFallbackReason.PLATFORM_FAILURE),
                )
            }
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
            val authority = requestedUrl.ownedStackAuthorityFromUrl()
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
