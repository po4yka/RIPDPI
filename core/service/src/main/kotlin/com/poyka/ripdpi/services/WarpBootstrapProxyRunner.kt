package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiHostsConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.BuiltInWarpControlPlaneHosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

data class WarpBootstrapProxyConfig(
    val host: String,
    val port: Int,
) {
    fun asOkHttpProxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
}

interface WarpBootstrapProxyRunner {
    suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T
}

@Singleton
class PassthroughWarpBootstrapProxyRunner
    @Inject
    constructor() : WarpBootstrapProxyRunner {
        override suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T = block(null)
    }

@Singleton
internal class ManagedWarpBootstrapProxyRunner
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val bootstrapProxyRuntimeSupervisorSessionFactory: BootstrapProxyRuntimeSupervisorSessionFactory,
        @param:ApplicationIoScope private val scope: CoroutineScope,
    ) : WarpBootstrapProxyRunner {
        override suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T {
            val bootstrapPort = reserveLoopbackPort()
            val basePreferences = RipDpiProxyUIPreferences.fromSettings(appSettingsRepository.snapshot())
            val bootstrapPreferences =
                RipDpiProxyUIPreferences(
                    protocols = basePreferences.protocols,
                    parserEvasions = basePreferences.parserEvasions,
                    adaptiveFallback = basePreferences.adaptiveFallback,
                    wsTunnel = basePreferences.wsTunnel,
                    listen = basePreferences.listen.copy(ip = LoopbackHost, port = bootstrapPort),
                    chains = basePreferences.chains,
                    fakePackets = basePreferences.fakePackets,
                    quic = basePreferences.quic,
                    hosts =
                        RipDpiHostsConfig(
                            mode = RipDpiHostsConfig.Mode.Whitelist,
                            entries = BuiltInWarpControlPlaneHosts.joinToString(separator = "\n"),
                        ),
                    relay = RipDpiRelayConfig(enabled = false),
                    warp = RipDpiWarpConfig(enabled = false),
                    hostAutolearn = basePreferences.hostAutolearn,
                    nativeLogLevel = basePreferences.nativeLogLevel,
                    runtimeContext = basePreferences.runtimeContext,
                    logContext = basePreferences.logContext,
                    rootMode = basePreferences.rootMode,
                    rootHelperSocketPath = basePreferences.rootHelperSocketPath,
                )
            val bootstrapScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            val proxyRuntimeSupervisor =
                bootstrapProxyRuntimeSupervisorSessionFactory.create(bootstrapScope)
            proxyRuntimeSupervisor.start(preferences = bootstrapPreferences, onUnexpectedExit = {})
            return try {
                block(WarpBootstrapProxyConfig(host = LoopbackHost, port = bootstrapPort))
            } finally {
                proxyRuntimeSupervisor.stop()
                bootstrapScope.cancel()
            }
        }

        private fun reserveLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

        private companion object {
            private const val LoopbackHost = "127.0.0.1"
        }
    }
