package com.poyka.ripdpi.strategy

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
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface StrategyPackDownloadService {
    suspend fun downloadManifest(url: String): Response<ResponseBody>

    suspend fun downloadCatalog(url: String): Response<ResponseBody>
}

private interface StrategyPackDownloadApi {
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

@Singleton
class DefaultStrategyPackDownloadService
    @Inject
    constructor(
        private val tlsClientFactory: OwnedTlsClientFactory,
    ) : StrategyPackDownloadService {
        override suspend fun downloadManifest(url: String): Response<ResponseBody> = api().downloadManifest(url)

        override suspend fun downloadCatalog(url: String): Response<ResponseBody> = api().downloadCatalog(url)

        private fun api(): StrategyPackDownloadApi =
            Retrofit
                .Builder()
                .baseUrl(STRATEGY_PACK_BASE_URL)
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
                                    .header("User-Agent", STRATEGY_PACK_USER_AGENT)
                                    .build(),
                            )
                        }
                    },
                ).build()
                .create(StrategyPackDownloadApi::class.java)
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

const val STRATEGY_PACK_BASE_URL = "https://raw.githubusercontent.com/"
const val STRATEGY_PACK_USER_AGENT = "RIPDPI strategy-pack catalog"
