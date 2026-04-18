package com.poyka.ripdpi.strategy

import com.poyka.ripdpi.core.DefaultNativeOwnedTlsCallTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsConnectTimeoutMs
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsMaxRedirects
import com.poyka.ripdpi.core.DefaultNativeOwnedTlsReadTimeoutMs
import com.poyka.ripdpi.core.NativeOwnedTlsHttpFetcher
import com.poyka.ripdpi.core.NativeOwnedTlsHttpRequest
import com.poyka.ripdpi.services.OwnedTlsClientFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private const val HttpSuccessStatusMin = 200
private const val HttpSuccessStatusMax = 299

interface StrategyPackDownloadService {
    suspend fun downloadManifest(url: String): ByteArray

    suspend fun downloadCatalog(url: String): ByteArray
}

data class StrategyPackBuildProvenance(
    val appVersion: String,
    val nativeVersion: String,
)

fun interface StrategyPackBuildProvenanceProvider {
    fun current(): StrategyPackBuildProvenance
}

@Singleton
class DefaultStrategyPackBuildProvenanceProvider
    @Inject
    constructor(
        @param:javax.inject.Named("nativeLibVersion") private val nativeVersion: String,
    ) : StrategyPackBuildProvenanceProvider {
        override fun current(): StrategyPackBuildProvenance =
            StrategyPackBuildProvenance(
                appVersion =
                    com.poyka.ripdpi.BuildConfig.VERSION_NAME
                        .substringBefore('-'),
                nativeVersion = nativeVersion,
            )
    }

@Singleton
class DefaultStrategyPackDownloadService
    @Inject
    constructor(
        private val nativeOwnedTlsHttpFetcher: NativeOwnedTlsHttpFetcher,
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) : StrategyPackDownloadService {
        override suspend fun downloadManifest(url: String): ByteArray = download(url)

        override suspend fun downloadCatalog(url: String): ByteArray = download(url)

        private suspend fun download(url: String): ByteArray {
            val authority = url.authorityFromUrl()
            val selection = tlsClientFactory.selectionForAuthority(authority)
            val response =
                nativeOwnedTlsHttpFetcher.execute(
                    NativeOwnedTlsHttpRequest(
                        url = url,
                        headers = mapOf("User-Agent" to strategyPackUserAgent),
                        tlsProfileId = selection.profileId,
                        connectTimeoutMs = DefaultNativeOwnedTlsConnectTimeoutMs,
                        readTimeoutMs = DefaultNativeOwnedTlsReadTimeoutMs,
                        callTimeoutMs = DefaultNativeOwnedTlsCallTimeoutMs,
                        maxRedirects = DefaultNativeOwnedTlsMaxRedirects,
                    ),
                )
            verifyOwnedTlsEchSelection(selection, response)
            if (response.statusCode in HttpSuccessStatusMin..HttpSuccessStatusMax) {
                return response.body
            }
            throw IOException("Remote request failed with HTTP ${response.statusCode} for ${response.finalUrl ?: url}")
        }

        private fun verifyOwnedTlsEchSelection(
            selection: com.poyka.ripdpi.services.OwnedTlsFingerprintSelection,
            response: com.poyka.ripdpi.core.NativeOwnedTlsHttpResult,
        ) {
            if (!selection.tlsTemplateEchCapable) {
                return
            }
            if (response.tlsTemplateEchCapable == false) {
                throw IOException(
                    "Native owned TLS fetch downgraded ECH-capable profile ${selection.profileId} to a non-ECH template",
                )
            }
            response.tlsTemplateEchBootstrapPolicy?.let { actual ->
                if (actual != selection.tlsTemplateEchBootstrapPolicy) {
                    throw IOException(
                        "Native owned TLS fetch returned ECH bootstrap policy $actual for ${selection.profileId} " +
                            "instead of ${selection.tlsTemplateEchBootstrapPolicy}",
                    )
                }
            }
            response.tlsTemplateEchBootstrapResolverId?.let { actual ->
                if (actual != selection.tlsTemplateEchBootstrapResolverId) {
                    throw IOException(
                        "Native owned TLS fetch returned ECH bootstrap resolver $actual for ${selection.profileId} " +
                            "instead of ${selection.tlsTemplateEchBootstrapResolverId}",
                    )
                }
            }
            response.tlsTemplateEchOuterExtensionPolicy?.let { actual ->
                if (actual != selection.tlsTemplateEchOuterExtensionPolicy) {
                    throw IOException(
                        "Native owned TLS fetch returned ECH outer-extension policy " +
                            "$actual for ${selection.profileId} " +
                            "instead of ${selection.tlsTemplateEchOuterExtensionPolicy}",
                    )
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackProvenanceBindingsModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackBuildProvenanceProvider(
        provider: DefaultStrategyPackBuildProvenanceProvider,
    ): StrategyPackBuildProvenanceProvider

    @Binds
    @Singleton
    abstract fun bindStrategyPackDownloadService(
        service: DefaultStrategyPackDownloadService,
    ): StrategyPackDownloadService
}

const val strategyPackBaseUrl = "https://raw.githubusercontent.com/"
const val strategyPackUserAgent = "RIPDPI strategy-pack catalog"

private fun String.authorityFromUrl(): String? = runCatching { URI(this).host }.getOrNull()
