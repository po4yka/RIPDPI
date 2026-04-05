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
                .baseUrl(HOST_PACK_CATALOG_BASE_URL)
                .client(
                    tlsClientFactory.create {
                        connectTimeout(20, TimeUnit.SECONDS)
                        readTimeout(90, TimeUnit.SECONDS)
                        callTimeout(120, TimeUnit.SECONDS)
                        addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("User-Agent", HOST_PACK_CATALOG_USER_AGENT)
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

const val HOST_PACK_CATALOG_BASE_URL = "https://raw.githubusercontent.com/"
const val HOST_PACK_CATALOG_USER_AGENT = "RIPDPI host-pack catalog"
