package com.poyka.ripdpi.platform

import com.poyka.ripdpi.core.detection.vpn.VpnAppCatalog
import com.poyka.ripdpi.services.VpnFamilyPackages
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DnsInvalidatorModule {
    @Provides
    @Singleton
    @VpnFamilyPackages
    fun provideVpnFamilyPackages(): Set<String> = VpnAppCatalog.signatures.map { it.packageName }.toSet()
}
