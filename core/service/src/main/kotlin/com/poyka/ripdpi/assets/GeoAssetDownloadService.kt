package com.poyka.ripdpi.assets

import com.poyka.ripdpi.services.OwnedTlsClientFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network access for the geo asset (geoip.db / geosite.db) picker. Mirrors
 * [com.poyka.ripdpi.hosts.HostPackCatalogNetwork]: each call builds a Retrofit client through the
 * app-owned TLS factory ([OwnedTlsClientFactory]), keyed on the target authority so the owned-stack
 * fingerprint policy applies. Raw URLs are never logged.
 */
interface GeoAssetDownloadService {
    /** Fetches the `/releases/latest` JSON for [apiUrl] (GitHub API). Returns the response body text. */
    suspend fun fetchLatestReleaseJson(apiUrl: String): String

    /** Streams the release asset bytes at [downloadUrl]. */
    suspend fun downloadAsset(downloadUrl: String): ByteArray
}

private interface GeoAssetDownloadApi {
    @GET
    suspend fun fetchJson(
        @Url url: String,
        @Header("Accept") accept: String,
    ): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun downloadBytes(
        @Url url: String,
    ): Response<ResponseBody>
}

@Singleton
class DefaultGeoAssetDownloadService
    @Inject
    constructor(
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) : GeoAssetDownloadService {
        override suspend fun fetchLatestReleaseJson(apiUrl: String): String =
            api(apiUrl)
                .fetchJson(apiUrl, githubApiAcceptHeader)
                .requireBodyText()

        override suspend fun downloadAsset(downloadUrl: String): ByteArray =
            api(downloadUrl)
                .downloadBytes(downloadUrl)
                .requireBodyBytes()

        private fun api(url: String): GeoAssetDownloadApi =
            Retrofit
                .Builder()
                .baseUrl(geoAssetBaseUrl)
                .client(
                    tlsClientFactory.createForAuthority(authority = url.authorityFromUrl()) {
                        connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                        readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                        callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                        addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("User-Agent", geoAssetUserAgent)
                                    .build(),
                            )
                        }
                    },
                ).build()
                .create(GeoAssetDownloadApi::class.java)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class GeoAssetDownloadServiceModule {
    @Binds
    @Singleton
    abstract fun bindGeoAssetDownloadService(service: DefaultGeoAssetDownloadService): GeoAssetDownloadService
}

private const val connectTimeoutSeconds = 20L
private const val readTimeoutSeconds = 90L
private const val callTimeoutSeconds = 120L

private const val geoAssetBaseUrl = "https://github.com/"
private const val geoAssetUserAgent = "RIPDPI geo-asset updater"
private const val githubApiAcceptHeader = "application/vnd.github+json"

private fun String.authorityFromUrl(): String? = runCatching { URI(this).host }.getOrNull()

private fun Response<ResponseBody>.requireBodyText(): String = requireSuccessfulBody().use(ResponseBody::string)

private fun Response<ResponseBody>.requireBodyBytes(): ByteArray = requireSuccessfulBody().use(ResponseBody::bytes)

private fun Response<ResponseBody>.requireSuccessfulBody(): ResponseBody {
    if (isSuccessful) {
        body()?.let { return it }
    }
    throw IOException("Geo asset request failed with HTTP ${code()}")
}
