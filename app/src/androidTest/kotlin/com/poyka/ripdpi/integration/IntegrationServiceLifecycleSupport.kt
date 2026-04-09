package com.poyka.ripdpi.integration

import android.content.Context
import android.content.Intent
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import kotlinx.coroutines.delay

internal data class ServiceLifecycleIntegrationBindings(
    val appSettingsRepository: AppSettingsRepository,
    val proxyPreferencesResolver: ProxyPreferencesResolver,
    val proxyFactory: RipDpiProxyFactory,
    val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    val serviceStateStore: ServiceStateStore,
    val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
)

internal fun resetServiceLifecycleIntegrationBindings(): ServiceLifecycleIntegrationBindings {
    IntegrationTestOverrides.reset()
    return ServiceLifecycleIntegrationBindings(
        appSettingsRepository = IntegrationTestOverrides.appSettingsRepository,
        proxyPreferencesResolver = IntegrationTestOverrides.proxyPreferencesResolver,
        proxyFactory = IntegrationTestOverrides.proxyFactory,
        tun2SocksBridgeFactory = IntegrationTestOverrides.tun2SocksBridgeFactory,
        serviceStateStore = IntegrationTestOverrides.serviceStateStore,
        vpnTunnelSessionProvider = IntegrationTestOverrides.vpnTunnelSessionProvider,
    )
}

internal suspend fun stopIntegrationTestServices(
    context: Context,
    settleDelayMs: Long = 200L,
) {
    context.startService(Intent(context, RipDpiProxyService::class.java).setAction(stopAction))
    context.startService(Intent(context, RipDpiVpnService::class.java).setAction(stopAction))
    delay(settleDelayMs)
}
