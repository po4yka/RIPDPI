package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedTorPluggableTransportConfig
import com.poyka.ripdpi.core.RipDpiRelayConfig

internal class StaticTorRuntimePathProvider(
    private val stateDir: String = "/tmp/ripdpi-tor/default/state",
    private val cacheDir: String = "/tmp/ripdpi-tor/default/cache",
) : TorRuntimePathProvider {
    override fun pathsFor(profileId: String): TorRuntimePaths =
        TorRuntimePaths(
            stateDir = stateDir,
            cacheDir = cacheDir,
        )
}

internal class StaticTorPluggableTransportProvider(
    private val transports: List<ResolvedTorPluggableTransportConfig> = emptyList(),
) : TorPluggableTransportProvider {
    override fun transportsFor(config: RipDpiRelayConfig): List<ResolvedTorPluggableTransportConfig> = transports
}
