package com.poyka.ripdpi.services

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Retrofit-based downloader for the shared-priors release channel
// Mirrors the existing host-pack catalog network in shape and TLS
// posture: same OwnedTlsClientFactory, same timeouts,
// same User-Agent convention.
//
// The manifest and priors payload live as static assets in a public
// GitHub repository; no project-operated backend is involved.

private interface SharedPriorsCatalogDownloadApi {
    // HEAD probe on the manifest -- the worker uses Last-Modified to
    // skip the body fetch when nothing has changed since the previous
    // refresh. Retrofit returns just headers when the upstream honours
    // HEAD; the body is empty either way.
    @HEAD
    suspend fun headManifest(
        @Url url: String,
    ): Response<Void>

    @GET
    suspend fun downloadManifest(
        @Url url: String,
    ): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun downloadPriors(
        @Url url: String,
    ): Response<ResponseBody>
}

interface SharedPriorsCatalogDownloadService {
    suspend fun headManifestLastModified(manifestUrl: String): String?

    suspend fun downloadManifest(manifestUrl: String): String

    suspend fun downloadPriors(priorsUrl: String): ByteArray
}

@Singleton
class DefaultSharedPriorsCatalogDownloadService
    @Inject
    constructor(
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) : SharedPriorsCatalogDownloadService {
        override suspend fun headManifestLastModified(manifestUrl: String): String? {
            val response = api(authority = manifestUrl.authorityFromUrl()).headManifest(manifestUrl)
            return if (response.isSuccessful) response.headers()["Last-Modified"] else null
        }

        override suspend fun downloadManifest(manifestUrl: String): String =
            api(authority = manifestUrl.authorityFromUrl())
                .downloadManifest(manifestUrl)
                .requireBodyBytes(
                    limit = SharedPriorsManifestMaxBytes,
                    label = "manifest",
                ).toString(Charsets.UTF_8)

        override suspend fun downloadPriors(priorsUrl: String): ByteArray =
            api(authority = priorsUrl.authorityFromUrl())
                .downloadPriors(priorsUrl)
                .requireBodyBytes(
                    limit = SharedPriorsPayloadMaxBytes,
                    label = "payload",
                )

        private fun api(authority: String?): SharedPriorsCatalogDownloadApi =
            Retrofit
                .Builder()
                .baseUrl(sharedPriorsCatalogBaseUrl)
                .client(
                    tlsClientFactory.createForAuthority(authority = authority) {
                        connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                        readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                        callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                        addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("User-Agent", sharedPriorsCatalogUserAgent)
                                    .build(),
                            )
                        }
                    },
                ).build()
                .create(SharedPriorsCatalogDownloadApi::class.java)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SharedPriorsCatalogNetworkModule {
    @Binds
    @Singleton
    abstract fun bindSharedPriorsCatalogDownloadService(
        service: DefaultSharedPriorsCatalogDownloadService,
    ): SharedPriorsCatalogDownloadService
}

private const val connectTimeoutSeconds = 20L
private const val readTimeoutSeconds = 90L
private const val callTimeoutSeconds = 120L

const val sharedPriorsCatalogBaseUrl = "https://raw.githubusercontent.com/"
const val sharedPriorsCatalogUserAgent = "RIPDPI shared-priors"

internal const val SharedPriorsManifestMaxBytes = 64 * 1024

// Mirrors ripdpi-shared-priors parser.rs MAX_RAW_PAYLOAD.
internal const val SharedPriorsPayloadMaxBytes = 256 * 1024

private const val boundedBodyReadChunkBytes = 8L * 1024L

private fun String.authorityFromUrl(): String? = runCatching { URI(this).host }.getOrNull()

private fun Response<ResponseBody>.requireBodyBytes(
    limit: Int,
    label: String,
): ByteArray = requireSuccessfulBody().use { body -> body.readBoundedBytes(limit = limit, label = label) }

private fun ResponseBody.readBoundedBytes(
    limit: Int,
    label: String,
): ByteArray {
    val declaredBytes = contentLength()
    if (declaredBytes >= 0L && declaredBytes > limit.toLong()) {
        throw java.io.IOException(
            "shared-priors $label declared body size $declaredBytes exceeds byte limit $limit",
        )
    }

    val maximumReadBytes = limit.toLong() + 1L
    val buffer = Buffer()
    val source = source()
    while (buffer.size < maximumReadBytes) {
        val remainingBytes = maximumReadBytes - buffer.size
        val readBytes = source.read(buffer, minOf(boundedBodyReadChunkBytes, remainingBytes))
        if (readBytes == -1L) break
    }
    if (buffer.size > limit.toLong()) {
        throw java.io.IOException(
            "shared-priors $label streamed body exceeds byte limit $limit",
        )
    }
    return buffer.readByteArray()
}

private fun Response<ResponseBody>.requireSuccessfulBody(): ResponseBody {
    if (isSuccessful) {
        body()?.let { return it }
    }
    throw java.io.IOException("Remote request failed with HTTP ${code()} for ${raw().request.url}")
}
