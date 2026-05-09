package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.ResolvedRipDpiRelayConfig
import java.net.InetSocketAddress

internal class SubprocessRelayBridgeOrchestrator {
    @Volatile private var managedClientListener: InetSocketAddress? = null

    @Volatile private var managedClientBridge: ManagedClientSocksBridge? = null

    fun noteManagedClientListener(listener: InetSocketAddress) {
        managedClientListener = listener
    }

    fun currentManagedClientListener(): InetSocketAddress? = managedClientListener

    fun startBridge(
        config: ResolvedRipDpiRelayConfig,
        bridgeSpec: ManagedClientSocksBridgeSpec,
        listener: InetSocketAddress,
    ) {
        managedClientBridge =
            ManagedClientSocksBridge(
                listenHost = config.localSocksHost,
                listenPort = config.localSocksPort,
                upstreamListener = listener,
                bridgeSpec = bridgeSpec,
            ).also { bridge ->
                bridge.start()
            }
    }

    fun reset() {
        managedClientBridge?.close()
        managedClientBridge = null
        managedClientListener = null
    }
}
