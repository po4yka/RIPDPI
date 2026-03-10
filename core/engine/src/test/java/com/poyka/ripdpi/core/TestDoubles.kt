package com.poyka.ripdpi.core

class FakeProxyPreferencesResolver(
    private var preferences: RipDpiProxyPreferences = RipDpiProxyUIPreferences(),
) : ProxyPreferencesResolver {
    override suspend fun resolve(): RipDpiProxyPreferences = preferences

    fun setPreferences(preferences: RipDpiProxyPreferences) {
        this.preferences = preferences
    }
}

class FakeRipDpiProxyFactory(
    private val proxy: RipDpiProxy,
) : RipDpiProxyFactory {
    override fun create(): RipDpiProxy = proxy
}

class FakeTun2SocksBridge : Tun2SocksBridge {
    var startedConfig: Tun2SocksConfig? = null
    var startedTunFd: Int? = null
    var stopCount: Int = 0
    var statsValue: TunnelStats = TunnelStats()

    override suspend fun start(
        config: Tun2SocksConfig,
        tunFd: Int,
    ) {
        startedConfig = config
        startedTunFd = tunFd
    }

    override suspend fun stop() {
        stopCount += 1
    }

    override suspend fun stats(): TunnelStats = statsValue
}

class FakeTun2SocksBridgeFactory(
    private val bridge: Tun2SocksBridge = FakeTun2SocksBridge(),
) : Tun2SocksBridgeFactory {
    override fun create(): Tun2SocksBridge = bridge
}
