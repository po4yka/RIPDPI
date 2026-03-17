package com.poyka.ripdpi.hosts

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

interface HostPackCatalogDownloadService {
    @Streaming
    @GET("runetfreedom/russia-blocked-geosite/release/geosite.dat")
    suspend fun downloadGeosite(): Response<ResponseBody>

    @GET("runetfreedom/russia-blocked-geosite/release/geosite.dat.sha256sum")
    suspend fun downloadChecksum(): Response<ResponseBody>
}

@Module
@InstallIn(SingletonComponent::class)
object HostPackCatalogNetworkModule {
    @Provides
    @Singleton
    fun provideHostPackCatalogOkHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .header("User-Agent", HOST_PACK_CATALOG_USER_AGENT)
                        .build(),
                )
            }.build()

    @Provides
    @Singleton
    fun provideHostPackCatalogDownloadService(client: OkHttpClient): HostPackCatalogDownloadService =
        Retrofit
            .Builder()
            .baseUrl(HOST_PACK_CATALOG_BASE_URL)
            .client(client)
            .build()
            .create(HostPackCatalogDownloadService::class.java)
}

const val HOST_PACK_CATALOG_BASE_URL = "https://raw.githubusercontent.com/"
const val HOST_PACK_CATALOG_USER_AGENT = "RIPDPI host-pack catalog"
