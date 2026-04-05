package com.poyka.ripdpi.strategy

import dagger.Binds
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
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface StrategyPackDownloadService {
    @GET
    suspend fun downloadManifest(
        @Url url: String,
    ): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun downloadCatalog(
        @Url url: String,
    ): Response<ResponseBody>
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

@Module
@InstallIn(SingletonComponent::class)
abstract class StrategyPackProvenanceBindingsModule {
    @Binds
    @Singleton
    abstract fun bindStrategyPackBuildProvenanceProvider(
        provider: DefaultStrategyPackBuildProvenanceProvider,
    ): StrategyPackBuildProvenanceProvider
}

@Module
@InstallIn(SingletonComponent::class)
object StrategyPackNetworkModule {
    @Provides
    @Singleton
    @javax.inject.Named("strategyPackCatalogClient")
    fun provideStrategyPackCatalogOkHttpClient(): OkHttpClient =
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
                        .header("User-Agent", STRATEGY_PACK_USER_AGENT)
                        .build(),
                )
            }.build()

    @Provides
    @Singleton
    fun provideStrategyPackDownloadService(
        @javax.inject.Named("strategyPackCatalogClient") client: OkHttpClient,
    ): StrategyPackDownloadService =
        Retrofit
            .Builder()
            .baseUrl(STRATEGY_PACK_BASE_URL)
            .client(client)
            .build()
            .create(StrategyPackDownloadService::class.java)
}

const val STRATEGY_PACK_BASE_URL = "https://raw.githubusercontent.com/"
const val STRATEGY_PACK_USER_AGENT = "RIPDPI strategy-pack catalog"
