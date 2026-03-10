package com.poyka.ripdpi.testing

import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.ProxyPreferencesResolverModule
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyFactoryModule
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsRepositoryModule
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceStateStoreModule
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.VpnTunnelSessionProviderModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppSettingsRepositoryModule::class],
)
object AppSettingsRepositoryTestModule {
    @Provides
    @Singleton
    fun provideAppSettingsRepository(): AppSettingsRepository = IntegrationTestOverrides.appSettingsRepository
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ProxyPreferencesResolverModule::class],
)
object ProxyPreferencesResolverTestModule {
    @Provides
    @Singleton
    fun provideProxyPreferencesResolver(): ProxyPreferencesResolver = IntegrationTestOverrides.proxyPreferencesResolver
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RipDpiProxyFactoryModule::class],
)
object RipDpiProxyFactoryTestModule {
    @Provides
    @Singleton
    fun provideRipDpiProxyFactory(): RipDpiProxyFactory = IntegrationTestOverrides.proxyFactory
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [Tun2SocksBridgeFactoryModule::class],
)
object Tun2SocksBridgeFactoryTestModule {
    @Provides
    @Singleton
    fun provideTun2SocksBridgeFactory(): Tun2SocksBridgeFactory = IntegrationTestOverrides.tun2SocksBridgeFactory
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ServiceStateStoreModule::class],
)
object ServiceStateStoreTestModule {
    @Provides
    @Singleton
    fun provideServiceStateStore(): ServiceStateStore = IntegrationTestOverrides.serviceStateStore
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [VpnTunnelSessionProviderModule::class],
)
object VpnTunnelSessionProviderTestModule {
    @Provides
    @Singleton
    fun provideVpnTunnelSessionProvider(): VpnTunnelSessionProvider =
        IntegrationTestOverrides.vpnTunnelSessionProvider
}
