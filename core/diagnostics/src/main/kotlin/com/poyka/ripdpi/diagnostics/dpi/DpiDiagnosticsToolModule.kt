package com.poyka.ripdpi.diagnostics.dpi

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHttpClientFactory
import com.poyka.ripdpi.diagnostics.dpich.HttpCompressionProber
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
    fun provideDnsIntegrityChecker(): DnsIntegrityChecker = DnsIntegrityChecker()

    @Provides
    fun provideDnsAvailabilitySurvey(): DnsAvailabilitySurvey = DnsAvailabilitySurvey()

    @Provides
    fun provideDomainReachabilityScanner(): DomainReachabilityScanner = DomainReachabilityScanner()

    @Provides
    fun provideTcp16FatHeaderProbe(): Tcp16FatHeaderProbe = Tcp16FatHeaderProbe()

    @Provides
    fun provideRknLayeredProbePipeline(): RknLayeredProbePipeline = RknLayeredProbePipeline()

    @Provides
    fun provideHttpCompressionProber(tlsClientFactory: DiagnosticsHttpClientFactory): HttpCompressionProber =
        HttpCompressionProber(clientBuilder = tlsClientFactory::createClient)

    @Provides
    fun provideSelfInfoFetcher(tlsClientFactory: DiagnosticsHttpClientFactory): SelfInfoFetcher =
        SelfInfoFetcher(clientBuilder = tlsClientFactory::createClient)
}
