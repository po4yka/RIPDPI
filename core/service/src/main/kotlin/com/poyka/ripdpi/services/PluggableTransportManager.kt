package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiRelayRuntime
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindWebTunnel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val SnowflakeBinaryName = "ripdpi-snowflake"
private const val WebTunnelBinaryName = "ripdpi-webtunnel"
private const val Obfs4BinaryName = "ripdpi-obfs4"

@Singleton
class PluggableTransportManager
    @Inject
    constructor(
        private val subprocessManager: SubprocessSocksRelayManager,
    ) {
        suspend fun start(config: ResolvedRipDpiRelayConfig) {
            subprocessManager.start(
                config = config,
                spec = launchSpec(config),
            )
        }

        suspend fun waitForExit(): Int = subprocessManager.waitForExit()

        suspend fun pollTelemetry(): NativeRuntimeSnapshot = subprocessManager.pollTelemetry()

        suspend fun stop() = subprocessManager.stop()

        private fun launchSpec(config: ResolvedRipDpiRelayConfig): SubprocessSocksRelayLaunchSpec =
            when (config.kind) {
                RelayKindSnowflake -> {
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = SnowflakeBinaryName,
                        runtimeKind = config.kind,
                        commandArguments =
                            buildList {
                                add("--listen")
                                add("${config.localSocksHost}:${config.localSocksPort}")
                                add("--broker-url")
                                add(config.ptSnowflakeBrokerUrl)
                                config.ptSnowflakeFrontDomain.takeIf(String::isNotBlank)?.let {
                                    add("--front-domain")
                                    add(it)
                                }
                            },
                    )
                }

                RelayKindWebTunnel -> {
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = WebTunnelBinaryName,
                        runtimeKind = config.kind,
                        upstreamAddress = config.ptWebTunnelUrl,
                        commandArguments =
                            buildList {
                                add("--listen")
                                add("${config.localSocksHost}:${config.localSocksPort}")
                                add("--url")
                                add(config.ptWebTunnelUrl)
                            },
                    )
                }

                RelayKindObfs4 -> {
                    SubprocessSocksRelayLaunchSpec(
                        binaryName = Obfs4BinaryName,
                        runtimeKind = config.kind,
                        upstreamAddress = config.server.takeIf(String::isNotBlank),
                        commandArguments =
                            buildList {
                                add("--listen")
                                add("${config.localSocksHost}:${config.localSocksPort}")
                                add("--bridge-line")
                                add(config.ptBridgeLine)
                            },
                    )
                }

                else -> {
                    error("Unsupported pluggable transport kind: ${config.kind}")
                }
            }
    }

class PluggableTransportRuntime
    @Inject
    constructor(
        private val manager: PluggableTransportManager,
    ) : RipDpiRelayRuntime {
        override suspend fun start(config: ResolvedRipDpiRelayConfig): Int {
            manager.start(config)
            return manager.waitForExit()
        }

        override suspend fun awaitReady(timeoutMillis: Long) {
            // `start()` blocks until the local SOCKS listener answers a probe.
        }

        override suspend fun stop() {
            manager.stop()
        }

        override suspend fun pollTelemetry(): NativeRuntimeSnapshot = manager.pollTelemetry()
    }

interface PluggableTransportRuntimeFactory {
    fun create(): RipDpiRelayRuntime
}

@Singleton
class DefaultPluggableTransportRuntimeFactory
    @Inject
    constructor(
        private val runtime: PluggableTransportRuntime,
    ) : PluggableTransportRuntimeFactory {
        override fun create(): RipDpiRelayRuntime = runtime
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PluggableTransportRuntimeFactoryModule {
    @Binds
    @Singleton
    abstract fun bindPluggableTransportRuntimeFactory(
        factory: DefaultPluggableTransportRuntimeFactory,
    ): PluggableTransportRuntimeFactory
}
