package com.poyka.ripdpi.services

internal class PassthroughWarpBootstrapProxyRunner : WarpBootstrapProxyRunner {
    override suspend fun <T> withBootstrapProxy(block: suspend (WarpBootstrapProxyConfig?) -> T): T = block(null)
}
