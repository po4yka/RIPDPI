package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface ProxyPreferencesResolver {
    suspend fun resolve(): RipDpiProxyPreferences
}

@Singleton
class DefaultProxyPreferencesResolver
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
    ) : ProxyPreferencesResolver {
        override suspend fun resolve(): RipDpiProxyPreferences {
            val settings = appSettingsRepository.snapshot()
            return if (settings.enableCmdSettings) {
                RipDpiProxyCmdPreferences(settings.cmdArgs)
            } else {
                RipDpiProxyUIPreferences(settings)
            }
        }
    }

interface RipDpiProxyFactory {
    fun create(): RipDpiProxy
}

@Singleton
class DefaultRipDpiProxyFactory
    @Inject
    constructor() : RipDpiProxyFactory {
        override fun create(): RipDpiProxy = RipDpiProxy()
    }

interface Tun2SocksBridge {
    suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    )

    suspend fun stop()

    suspend fun stats(): TunnelStats
}

class NativeTun2SocksBridge
    @Inject
    constructor() : Tun2SocksBridge {
        private val tunnel = Tun2SocksTunnel()

        override suspend fun start(
            config: Tun2SocksConfig,
            tunFd: Int,
        ) {
            tunnel.start(config, tunFd)
        }

        override suspend fun stop() {
            tunnel.stop()
        }

        override suspend fun stats(): TunnelStats = tunnel.stats()
    }

interface Tun2SocksBridgeFactory {
    fun create(): Tun2SocksBridge
}

@Singleton
class DefaultTun2SocksBridgeFactory
    @Inject
    constructor() : Tun2SocksBridgeFactory {
        override fun create(): Tun2SocksBridge = NativeTun2SocksBridge()
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindingsModule {
    @Binds
    @Singleton
    abstract fun bindProxyPreferencesResolver(
        resolver: DefaultProxyPreferencesResolver,
    ): ProxyPreferencesResolver

    @Binds
    @Singleton
    abstract fun bindRipDpiProxyFactory(
        factory: DefaultRipDpiProxyFactory,
    ): RipDpiProxyFactory

    @Binds
    @Singleton
    abstract fun bindTun2SocksBridgeFactory(
        factory: DefaultTun2SocksBridgeFactory,
    ): Tun2SocksBridgeFactory
}
