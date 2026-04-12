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
            if (response.statusCode in HttpSuccessStatusMin..HttpSuccessStatusMax) {
                return response.body
            }
            throw IOException("Remote request failed with HTTP ${response.statusCode} for ${response.finalUrl ?: url}")
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
