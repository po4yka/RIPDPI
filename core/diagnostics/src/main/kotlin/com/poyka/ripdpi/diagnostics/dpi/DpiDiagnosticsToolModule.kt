package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHttpClientFactory
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapSpoofingDetector
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.dpich.KnownDohProviderSubnetMetadataLookup
import com.poyka.ripdpi.diagnostics.dpich.OkHttpWebhostProbe
import com.poyka.ripdpi.diagnostics.dpich.WebhostFarm
import com.poyka.ripdpi.diagnostics.dpich.loadDohProviderFilters
import com.poyka.ripdpi.diagnostics.rkn.HttpClientRknTlsProbe
import com.poyka.ripdpi.diagnostics.rkn.OkHttpRknHttpProbe
import com.poyka.ripdpi.diagnostics.rkn.RknLayeredProbePipeline
import com.poyka.ripdpi.diagnostics.rkn.SelfInfoFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object DpiDiagnosticsToolModule {
    @Provides
    fun provideDnsIntegrityChecker(
        client: DoqQuicClient,
        assetLoader: DpiAssetLoader,
    ): DnsIntegrityChecker =
        DnsIntegrityChecker(
            doqProbe = if (DpiDiagnosticsRuntimeFlags.includeQuic) DoqIntegrityProbe(client) else null,
            dohBootstrapDetector =
                DohBootstrapSpoofingDetector(
                    metadata = KnownDohProviderSubnetMetadataLookup(),
                    providers = assetLoader.loadDohProviderFilters(),
                ),
        )

    @Provides
    fun provideDoqQuicClient(bindings: NativeDoqQuicClientBindings): DoqQuicClient = NativeDoqQuicClient(bindings)

    @Provides
    fun provideNativeDoqQuicClientBindings(): NativeDoqQuicClientBindings = NativeDoqQuicClientNativeBindings()

    @Provides
    fun provideDnsAvailabilitySurvey(): DnsAvailabilitySurvey = DnsAvailabilitySurvey()

    @Provides
    fun provideDomainReachabilityScanner(tlsClientFactory: DiagnosticsHttpClientFactory): DomainReachabilityScanner =
        DomainReachabilityScanner(
            tlsClientStateProvider = tlsClientFactory::tlsClientState,
            attemptRunner =
                OkHttpDomainReachabilityAttemptRunner(
                    clientBuilder = tlsClientFactory::createClient,
                )::invoke,
        )

    @Provides
    fun provideTcp16FatHeaderProbe(tlsClientFactory: DiagnosticsHttpClientFactory): Tcp16FatHeaderProbe =
        Tcp16FatHeaderProbe(
            requestRunner = OkHttpTcp16RequestRunner.withClientBuilder(tlsClientFactory::createClient),
            tlsClientStateProvider = tlsClientFactory::tlsClientState,
        )

    @Provides
    fun provideRknLayeredProbePipeline(tlsClientFactory: DiagnosticsHttpClientFactory): RknLayeredProbePipeline =
        RknLayeredProbePipeline(
            tlsProbe = HttpClientRknTlsProbe(clientBuilder = tlsClientFactory::createClient),
            httpProbe = OkHttpRknHttpProbe(baseClient = tlsClientFactory.createClient()),
            tlsClientStateProvider = tlsClientFactory::tlsClientState,
        )

    @Provides
    fun provideHttpCompressionProber(tlsClientFactory: DiagnosticsHttpClientFactory): HttpCompressionProber =
        HttpCompressionProber(clientBuilder = tlsClientFactory::createClient)

    @Provides
    fun provideWebhostFarm(tlsClientFactory: DiagnosticsHttpClientFactory): WebhostFarm =
        WebhostFarm(probe = OkHttpWebhostProbe(clientBuilder = tlsClientFactory::createClient))

    @Provides
    fun provideSelfInfoFetcher(tlsClientFactory: DiagnosticsHttpClientFactory): SelfInfoFetcher =
        SelfInfoFetcher(clientBuilder = tlsClientFactory::createClient)
}
