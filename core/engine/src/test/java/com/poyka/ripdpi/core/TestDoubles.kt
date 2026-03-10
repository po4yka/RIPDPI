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
    private val proxy: RipDpiProxyRuntime,
) : RipDpiProxyFactory {
    override fun create(): RipDpiProxyRuntime = proxy
}

class FakeRipDpiProxyRuntime : RipDpiProxyRuntime {
    var startResult: Int = 0
    var stopCount: Int = 0
    var startCount: Int = 0
    var lastPreferences: RipDpiProxyPreferences? = null

    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        startCount += 1
        lastPreferences = preferences
        return startResult
    }

    override suspend fun stopProxy() {
        stopCount += 1
    }
}

class FakeRipDpiProxyBindings : RipDpiProxyBindings {
    var createdHandle: Long = 1L
    var startResult: Int = 0
    var lastCreatePayload: String? = null
    var lastStartedHandle: Long? = null
    var lastStoppedHandle: Long? = null
    var lastDestroyedHandle: Long? = null

    override fun create(configJson: String): Long {
        lastCreatePayload = configJson
        return createdHandle
    }

    override fun start(handle: Long): Int {
        lastStartedHandle = handle
        return startResult
    }

    override fun stop(handle: Long) {
        lastStoppedHandle = handle
    }

    override fun destroy(handle: Long) {
        lastDestroyedHandle = handle
    }
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

class FakeTun2SocksBindings : Tun2SocksBindings {
    var createdHandle: Long = 1L
    var lastCreatePayload: String? = null
    var lastStartHandle: Long? = null
    var lastStartTunFd: Int? = null
    var lastStopHandle: Long? = null
    var lastDestroyedHandle: Long? = null
    var nativeStats: LongArray = longArrayOf()

    override fun create(configJson: String): Long {
        lastCreatePayload = configJson
        return createdHandle
    }

    override fun start(
        handle: Long,
        tunFd: Int,
    ) {
        lastStartHandle = handle
        lastStartTunFd = tunFd
    }

    override fun stop(handle: Long) {
        lastStopHandle = handle
    }

    override fun getStats(handle: Long): LongArray = nativeStats

    override fun destroy(handle: Long) {
        lastDestroyedHandle = handle
    }
}
