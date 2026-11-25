package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.diagnostics.StrategyProbeActivator
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeRunGuard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProbeReplayModule {
    private const val ReplayProxyHost = "127.0.0.1"
    private const val ReplayProxyPort = 1080

    @Provides
    @Singleton
    fun provideScanCandidateConfigSource(
        scanRecordStore: DiagnosticsScanRecordStore,
        @Named("diagnosticsJson") json: Json,
    ): ScanCandidateConfigSource = StoredScanCandidateConfigSource(scanRecordStore, json)

    @Provides
    @Singleton
    fun provideProbeReplayService(
        impl: DefaultProbeReplayService,
        candidateProvider: StrategyProbeCandidateProvider,
        activator: StrategyProbeActivator,
        appSettingsRepository: AppSettingsRepository,
        configSource: ScanCandidateConfigSource,
        runGuard: StrategyProbeRunGuard,
    ): ProbeReplayService =
        StrategyScopedProbeReplay(
            delegate = impl,
            candidateProvider = candidateProvider,
            activator = activator,
            proxyConfigApplier = SettingsProxyConfigStrategyApplier(appSettingsRepository),
            configSource = configSource,
            runGuard = runGuard,
        )

    @Provides
    @Singleton
    fun provideReplayHttpClientFactory(appSettingsRepository: AppSettingsRepository): ReplayHttpClientFactory =
        ReplayHttpClientFactory { request, listener ->
            val settings = appSettingsRepository.snapshot()
            val host = settings.proxyIp.ifBlank { ReplayProxyHost }
            val port = settings.proxyPort.takeIf { it > 0 } ?: ReplayProxyPort
            val client =
                OkHttpClient
                    .Builder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)))
                    .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                    .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                    .eventListener(listener)
                    .build()
            ReplayHttpClient(
                client = client,
                url = "https://${request.domain}/".toHttpUrl(),
            )
        }
}
