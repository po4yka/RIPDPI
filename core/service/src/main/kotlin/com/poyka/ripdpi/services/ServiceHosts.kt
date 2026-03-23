package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.TunnelStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal interface ServiceHost {
    val serviceScope: CoroutineScope
    val backgroundDispatcher: CoroutineDispatcher

    fun startForegroundService()

    fun stopService(startId: Int?)
}

internal interface ProxyServiceHost : ServiceHost {
    fun updateRunningNotification(
        startedAt: Long,
        proxyTelemetry: NativeRuntimeSnapshot,
    )
}

interface VpnTunnelBuilder {
    fun establish(): VpnTunnelSession?
}

interface VpnTunnelBuilderHost {
    fun createTunnelBuilder(
        dns: String,
        ipv6: Boolean,
    ): VpnTunnelBuilder
}

internal interface VpnServiceHost :
    ServiceHost,
    VpnTunnelBuilderHost {
    fun updateRunningNotification(
        startedAt: Long,
        tunnelStats: TunnelStats,
        proxyTelemetry: NativeRuntimeSnapshot,
    )

    fun syncUnderlyingNetworksFromActiveNetwork()
}
