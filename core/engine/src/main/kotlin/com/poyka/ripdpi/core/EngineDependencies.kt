package com.poyka.ripdpi.core

import android.content.Context
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.TunnelStats
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val strategyPackStateStore: StrategyPackStateStore,
    ) : ProxyPreferencesResolver {
        override suspend fun resolve(): RipDpiProxyPreferences {
            val settings = appSettingsRepository.snapshot()
            val hostAutolearnStorePath = resolveHostAutolearnStorePath(context)
            val morphPolicy =
                strategyPackStateStore.state.value.morphPolicy
                    ?.toRipDpiMorphPolicy()
            return if (settings.enableCmdSettings) {
                RipDpiProxyCmdPreferences(settings.cmdArgs, hostAutolearnStorePath, runtimeContext = null)
            } else {
                RipDpiProxyUIPreferences.fromSettings(
                    settings,
                    hostAutolearnStorePath,
                    runtimeContext = RipDpiRuntimeContext(morphPolicy = morphPolicy),
                )
            }
        }
    }

interface RipDpiProxyFactory {
    fun create(): RipDpiProxyRuntime
}

@Singleton
class DefaultRipDpiProxyFactory
    @Inject
    constructor(
        private val nativeBindings: RipDpiProxyBindings,
    ) : RipDpiProxyFactory {
        override fun create(): RipDpiProxyRuntime = RipDpiProxy(nativeBindings)
    }

interface RipDpiWarpFactory {
    fun create(): RipDpiWarpRuntime
}

@Singleton
class DefaultRipDpiWarpFactory
    @Inject
    constructor(
        private val nativeBindings: RipDpiWarpBindings,
    ) : RipDpiWarpFactory {
        override fun create(): RipDpiWarpRuntime = RipDpiWarp(nativeBindings)
    }

interface RipDpiRelayFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultRipDpiRelayFactory
    @Inject
    constructor(
        private val nativeBindings: RipDpiRelayBindings,
    ) : RipDpiRelayFactory {
        override fun create(): RipDpiRelayRuntime = RipDpiRelay(nativeBindings)
    }

interface Tun2SocksBridge {
    suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    )

    suspend fun stop()

    suspend fun stats(): TunnelStats

    suspend fun telemetry(): NativeRuntimeSnapshot
}

class NativeTun2SocksBridge
    @Inject
    constructor(
        private val nativeBindings: Tun2SocksBindings,
    ) : Tun2SocksBridge {
        private val tunnel = Tun2SocksTunnel(nativeBindings)

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

        override suspend fun telemetry(): NativeRuntimeSnapshot = tunnel.telemetry()
    }

interface Tun2SocksBridgeFactory {
    fun create(): Tun2SocksBridge
}

@Singleton
class DefaultTun2SocksBridgeFactory
    @Inject
    constructor(
        private val nativeBindings: Tun2SocksBindings,
    ) : Tun2SocksBridgeFactory {
        override fun create(): Tun2SocksBridge = NativeTun2SocksBridge(nativeBindings)
    }

interface NetworkDiagnosticsBridgeFactory {
    fun create(): NetworkDiagnosticsBridge
}

@Singleton
class DefaultNetworkDiagnosticsBridgeFactory
    @Inject
    constructor(
        private val bindings: NetworkDiagnosticsBindings,
    ) : NetworkDiagnosticsBridgeFactory {
        override fun create(): NetworkDiagnosticsBridge = NetworkDiagnostics(bindings)
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ProxyPreferencesResolverModule {
    @Binds
    @Singleton
    abstract fun bindProxyPreferencesResolver(resolver: DefaultProxyPreferencesResolver): ProxyPreferencesResolver
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RipDpiProxyBindingsModule {
    @Binds
    @Singleton
    abstract fun bindRipDpiProxyBindings(bindings: RipDpiProxyNativeBindings): RipDpiProxyBindings
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RipDpiProxyFactoryModule {
    @Binds
    @Singleton
    abstract fun bindRipDpiProxyFactory(factory: DefaultRipDpiProxyFactory): RipDpiProxyFactory
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RipDpiWarpBindingsModule {
    @Binds
    @Singleton
    abstract fun bindRipDpiWarpBindings(bindings: RipDpiWarpNativeBindings): RipDpiWarpBindings

    @Binds
    @Singleton
    abstract fun bindRipDpiWarpProvisioningBindings(bindings: RipDpiWarpNativeBindings): RipDpiWarpProvisioningBindings
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RipDpiWarpFactoryModule {
    @Binds
    @Singleton
    abstract fun bindRipDpiWarpFactory(factory: DefaultRipDpiWarpFactory): RipDpiWarpFactory
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RipDpiRelayBindingsModule {
    @Binds
    @Singleton
    abstract fun bindRipDpiRelayBindings(bindings: RipDpiRelayNativeBindings): RipDpiRelayBindings
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RipDpiRelayFactoryModule {
    @Binds
    @Singleton
    abstract fun bindRipDpiRelayFactory(factory: DefaultRipDpiRelayFactory): RipDpiRelayFactory
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Tun2SocksBindingsModule {
    @Binds
    @Singleton
    abstract fun bindTun2SocksBindings(bindings: Tun2SocksNativeBindings): Tun2SocksBindings
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Tun2SocksBridgeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindTun2SocksBridgeFactory(factory: DefaultTun2SocksBridgeFactory): Tun2SocksBridgeFactory
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkDiagnosticsBridgeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindNetworkDiagnosticsBridgeFactory(
        factory: DefaultNetworkDiagnosticsBridgeFactory,
    ): NetworkDiagnosticsBridgeFactory
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkDiagnosticsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindNetworkDiagnosticsBindings(bindings: NetworkDiagnosticsNativeBindings): NetworkDiagnosticsBindings
}
