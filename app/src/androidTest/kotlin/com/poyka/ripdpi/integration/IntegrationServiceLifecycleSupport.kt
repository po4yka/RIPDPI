package com.poyka.ripdpi.integration

import android.content.Context
import android.content.Intent
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import com.poyka.ripdpi.testing.RecordingNetworkHandoverMonitor
import com.poyka.ripdpi.testing.RecordingPermissionWatchdog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

internal data class ServiceLifecycleIntegrationBindings(
    val appSettingsRepository: AppSettingsRepository,
    val proxyPreferencesResolver: ProxyPreferencesResolver,
    val proxyFactory: RipDpiProxyFactory,
    val tun2SocksBridgeFactory: Tun2SocksBridgeFactory,
    val serviceStateStore: ServiceStateStore,
    val vpnTunnelSessionProvider: VpnTunnelSessionProvider,
    val networkHandoverMonitor: RecordingNetworkHandoverMonitor,
    val permissionWatchdog: RecordingPermissionWatchdog,
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
        networkHandoverMonitor = IntegrationTestOverrides.networkHandoverMonitor,
        permissionWatchdog = IntegrationTestOverrides.permissionWatchdog,
    )
}

internal suspend fun stopIntegrationTestServices(
    context: Context,
    settleDelayMs: Long = 200L,
) {
    EntryPointAccessors
        .fromApplication(context, IntegrationServiceControllerEntryPoint::class.java)
        .serviceController()
        .stop()
    context.stopService(Intent(context, RipDpiProxyService::class.java))
    context.stopService(Intent(context, RipDpiVpnService::class.java))
    delay(settleDelayMs)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IntegrationServiceControllerEntryPoint {
    fun serviceController(): ServiceController
}
