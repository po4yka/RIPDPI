package com.poyka.ripdpi.testing

import com.poyka.ripdpi.diagnostics.PublicIpInfo
import com.poyka.ripdpi.diagnostics.PublicIpInfoResolver
import com.poyka.ripdpi.diagnostics.PublicIpInfoResolverModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [PublicIpInfoResolverModule::class],
)
object PublicIpInfoResolverTestModule {
    @Provides
    @Singleton
    fun providePublicIpInfoResolver(): PublicIpInfoResolver =
        object : PublicIpInfoResolver {
            override suspend fun resolve(): PublicIpInfo = PublicIpInfo(ip = "198.18.0.10", asn = "LOCAL")
        }
}
