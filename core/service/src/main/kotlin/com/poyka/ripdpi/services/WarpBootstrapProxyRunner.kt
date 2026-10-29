package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.service.warp.WarpBootstrapLoopbackPortAllocator
import com.poyka.ripdpi.service.warp.WarpBootstrapProxyRuntimePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
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
internal class ManagedWarpBootstrapProxyRunner
    @Inject
    constructor(
        private val portAllocator: WarpBootstrapLoopbackPortAllocator,
        private val runtimePolicy: WarpBootstrapProxyRuntimePolicy,
        private val bootstrapProxyRuntimeSupervisorSessionFactory: BootstrapProxyRuntimeSupervisorSessionFactory,
        @param:ApplicationIoScope private val scope: CoroutineScope,
    ) : WarpBootstrapProxyRunner {
        override suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T {
            val bootstrapPort = portAllocator.reserve()
            val bootstrapPreferences = runtimePolicy.preferencesFor(bootstrapPort)
            val bootstrapScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            try {
                val proxyRuntimeSupervisor =
                    bootstrapProxyRuntimeSupervisorSessionFactory.create(bootstrapScope)
                proxyRuntimeSupervisor.start(preferences = bootstrapPreferences, onUnexpectedExit = {})
                val result = runCatching { block(WarpBootstrapProxyConfig(host = LoopbackHost, port = bootstrapPort)) }
                // Stop even when the caller was cancelled, and never let a cleanup failure hide its outcome.
                val stopFailure =
                    withContext(NonCancellable) { runCatching { proxyRuntimeSupervisor.stop() }.exceptionOrNull() }
                result.exceptionOrNull()?.let { failure ->
                    stopFailure?.let(failure::addSuppressed)
                    throw failure
                }
                stopFailure?.let { throw it }
                return result.getOrThrow()
            } finally {
                bootstrapScope.cancel()
            }
        }

        private companion object {
            private const val LoopbackHost = "127.0.0.1"
        }
    }
