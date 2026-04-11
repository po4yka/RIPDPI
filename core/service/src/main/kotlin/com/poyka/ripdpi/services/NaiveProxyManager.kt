package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val NaiveProxyBinaryName = "ripdpi-naiveproxy"

@Singleton
class NaiveProxyManager
    @Inject
    constructor(
        private val subprocessManager: SubprocessSocksRelayManager,
    ) {
        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            subprocessManager.start(
                config = config,
                spec =
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = NaiveProxyBinaryName,
                        runtimeKind = config.kind,
                        upstreamAddress = "${config.server}:${config.serverPort}",
                        commandArguments =
                            buildList {
                                add("--listen")
                                add("${config.localSocksHost}:${config.localSocksPort}")
                                add("--server")
                                add(config.server)
                                add("--server-port")
                                add(config.serverPort.toString())
                                add("--server-name")
                                add(config.serverName)
                                config.naiveUsername?.let {
                                    add("--username")
                                    add(it)
                                }
                                config.naivePassword?.let {
                                    add("--password")
                                    add(it)
                                }
                                config.naivePath.takeIf(String::isNotBlank)?.let {
                                    add("--path")
                                    add(it)
                                }
                            },
                    ),
            )
        }

        suspend fun waitForExit(): Int = subprocessManager.waitForExit()

        suspend fun pollTelemetry() = subprocessManager.pollTelemetry()

        suspend fun stop() = subprocessManager.stop()
    }

class NaiveProxyRuntime
    @Inject
    constructor(
        private val manager: NaiveProxyManager,
    ) : RipDpiRelayRuntime {
        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            manager.start(config)
            return manager.waitForExit()
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            // `start()` waits for a successful health check before returning.
        }

        override suspend fun stop() {
            manager.stop()
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = manager.pollTelemetry()
    }

interface NaiveProxyRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultNaiveProxyRuntimeFactory
    @Inject
    constructor(
        private val runtime: NaiveProxyRuntime,
    ) : NaiveProxyRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class NaiveProxyRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindNaiveProxyRuntimeFactory(factory: DefaultNaiveProxyRuntimeFactory): NaiveProxyRuntimeFactory
}
