package com.poyka.ripdpi.diagnostics.dpi

import android.content.Context
import com.poyka.ripdpi.core.RipDpiProxyBindings
import com.poyka.ripdpi.core.resolveGeoDatabasePaths
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHttpClientFactory
import com.poyka.ripdpi.diagnostics.dpich.CidrWhitelistDetector
import com.poyka.ripdpi.diagnostics.dpich.CompositeSubnetMetadataLookup
import com.poyka.ripdpi.diagnostics.dpich.DohBootstrapSpoofingDetector
import com.poyka.ripdpi.diagnostics.dpich.EngineGeoIpSubnetMetadataLookup
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
import com.poyka.ripdpi.diagnostics.dpich.Ipv4WhitelistedSubnetDiscoverer
import com.poyka.ripdpi.diagnostics.dpich.KnownDohProviderSubnetMetadataLookup
import com.poyka.ripdpi.diagnostics.dpich.OkHttpCidrWhitelistUrlProbe
import com.poyka.ripdpi.diagnostics.dpich.OkHttpSubnetAliveProbe
import com.poyka.ripdpi.diagnostics.dpich.OkHttpWebhostProbe
import com.poyka.ripdpi.diagnostics.dpich.RipeStatClient
import com.poyka.ripdpi.diagnostics.dpich.RuntimeSubnetMetadataLookup
import com.poyka.ripdpi.diagnostics.dpich.SubnetsCache
import com.poyka.ripdpi.diagnostics.dpich.TlsKeylogRunFinalizer
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File

@Module
@InstallIn(ViewModelComponent::class)
object DpiDiagnosticsToolModule {
    @Provides
    fun provideDnsIntegrityChecker(
        @ApplicationContext context: Context,
        client: DoqQuicClient,
        assetLoader: DpiAssetLoader,
        proxyBindings: RipDpiProxyBindings,
    ): DnsIntegrityChecker =
        DnsIntegrityChecker(
            doqProbe = if (DpiDiagnosticsRuntimeFlags.includeQuic) DoqIntegrityProbe(client) else null,
            dohBootstrapDetector =
                DohBootstrapSpoofingDetector(
                    metadata =
                        CompositeSubnetMetadataLookup(
                            listOf(
                                EngineGeoIpSubnetMetadataLookup(
                                    bindings = proxyBindings,
                                    paths = resolveGeoDatabasePaths(context),
                                ),
                                RuntimeSubnetMetadataLookup(assetLoader.loadDohBootstrapSubnetMetadata()),
                                KnownDohProviderSubnetMetadataLookup(),
                            ),
                        ),
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
}

@Module
@InstallIn(ViewModelComponent::class)
object DpichDiagnosticsToolModule {
    @Provides
    fun provideCidrWhitelistDetector(
        assetLoader: DpiAssetLoader,
        tlsClientFactory: DiagnosticsHttpClientFactory,
    ): CidrWhitelistDetector =
        CidrWhitelistDetector(
            whitelistedUrls = assetLoader.loadCidrWhitelistedUrls(),
            regularUrls = assetLoader.loadCidrRegularUrls(),
            urlProbe = OkHttpCidrWhitelistUrlProbe(clientBuilder = tlsClientFactory::createClient),
        )

    @Provides
    fun provideIpv4WhitelistedSubnetDiscoverer(
        @ApplicationContext context: Context,
        assetLoader: DpiAssetLoader,
        tlsClientFactory: DiagnosticsHttpClientFactory,
    ): Ipv4WhitelistedSubnetDiscoverer =
        Ipv4WhitelistedSubnetDiscoverer(
            asns = assetLoader.loadIpv4WhitelistAsns(),
            ripeStat = RipeStatClient(httpClient = tlsClientFactory.createClient()),
            cache = SubnetsCache(File(context.filesDir, "dpich/whitelisted_subnets_cache.json")),
            aliveProbe = OkHttpSubnetAliveProbe(httpClient = tlsClientFactory.createClient()),
        )

    @Provides
    fun provideWebhostFarm(tlsClientFactory: DiagnosticsHttpClientFactory): WebhostFarm =
        WebhostFarm(probe = OkHttpWebhostProbe(clientBuilder = tlsClientFactory::createClient))

    @Provides
    fun provideSelfInfoFetcher(tlsClientFactory: DiagnosticsHttpClientFactory): SelfInfoFetcher =
        SelfInfoFetcher(clientBuilder = tlsClientFactory::createClient)

    @Provides
    fun provideTlsKeylogRunFinalizer(): TlsKeylogRunFinalizer = TlsKeylogRunFinalizer()

    @Provides
    fun provideEchTlsHandshake(): EchTlsHandshake = NativeEchTlsHandshake()
}
