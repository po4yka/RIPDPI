package com.poyka.ripdpi.diagnostics.dpi

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
    fun provideDomainReachabilityScanner(): DomainReachabilityScanner = DomainReachabilityScanner()
}
