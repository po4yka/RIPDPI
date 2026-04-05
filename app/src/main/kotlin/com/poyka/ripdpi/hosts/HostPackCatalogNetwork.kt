package com.poyka.ripdpi.hosts

import com.poyka.ripdpi.services.OwnedTlsClientFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface HostPackCatalogDownloadService {
    suspend fun downloadGeosite(): Response<ResponseBody>

    suspend fun downloadChecksum(): Response<ResponseBody>
}

private interface HostPackCatalogDownloadApi {
    @Streaming
    @GET("runetfreedom/russia-blocked-geosite/release/geosite.dat")
    suspend fun downloadGeosite(): Response<ResponseBody>

    @GET("runetfreedom/russia-blocked-geosite/release/geosite.dat.sha256sum")
    suspend fun downloadChecksum(): Response<ResponseBody>
}

@Singleton
class DefaultHostPackCatalogDownloadService
    @Inject
    constructor(
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) : HostPackCatalogDownloadService {
        override suspend fun downloadGeosite(): Response<ResponseBody> = api().downloadGeosite()

        override suspend fun downloadChecksum(): Response<ResponseBody> = api().downloadChecksum()

        private fun api(): HostPackCatalogDownloadApi =
            Retrofit
                .Builder()
                .baseUrl(hostPackCatalogBaseUrl)
                .client(
                    tlsClientFactory.create {
                        connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                        readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                        callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                        addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("User-Agent", hostPackCatalogUserAgent)
                                    .build(),
                            )
                        }
                    },
                ).build()
                .create(HostPackCatalogDownloadApi::class.java)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class HostPackCatalogNetworkModule {
    @Binds
    @Singleton
    abstract fun bindHostPackCatalogDownloadService(
        service: DefaultHostPackCatalogDownloadService,
    ): HostPackCatalogDownloadService
}

private const val connectTimeoutSeconds = 20L
private const val readTimeoutSeconds = 90L
private const val callTimeoutSeconds = 120L

const val hostPackCatalogBaseUrl = "https://raw.githubusercontent.com/"
const val hostPackCatalogUserAgent = "RIPDPI host-pack catalog"
