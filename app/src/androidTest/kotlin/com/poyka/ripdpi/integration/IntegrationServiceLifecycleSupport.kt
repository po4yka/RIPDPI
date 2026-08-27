package com.poyka.ripdpi.integration

import android.content.Context
import android.content.Intent
import com.poyka.ripdpi.core.ProxyPreferencesResolver
import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.Tun2SocksBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.services.AcceptedUserStopRecorder
import com.poyka.ripdpi.services.RipDpiProxyService
import com.poyka.ripdpi.services.RipDpiVpnService
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.services.VpnTunnelSessionProvider
import com.poyka.ripdpi.services.explicitUserIntentGenerationExtra
import com.poyka.ripdpi.testing.IntegrationTestOverrides
import com.poyka.ripdpi.testing.RecordingNetworkHandoverMonitor
import com.poyka.ripdpi.testing.RecordingPermissionWatchdog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

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
    val entryPoint =
        EntryPointAccessors.fromApplication(context, IntegrationServiceCleanupEntryPoint::class.java)
    val stateStore = entryPoint.serviceStateStore()
    val arbiter = entryPoint.serviceIntentArbiter()
    try {
        arbiter.serialize {
            entryPoint.acceptedUserStopRecorder().record()
            val (status, mode) = stateStore.status.value
            if (status != AppStatus.Halted) {
                val serviceClass =
                    if (mode == Mode.VPN) RipDpiVpnService::class.java else RipDpiProxyService::class.java
                // Cleanup must not create a new foreground-service obligation before hard stop.
                context.startService(
                    Intent(context, serviceClass).setAction(stopAction).putExtra(
                        explicitUserIntentGenerationExtra,
                        arbiter.captureExplicitUserIntentGeneration(),
                    ),
                )
            }
        }
        withTimeout(10_000L) {
            stateStore.status.first { (status, _) -> status == AppStatus.Halted }
        }
    } finally {
        context.stopService(Intent(context, RipDpiProxyService::class.java))
        context.stopService(Intent(context, RipDpiVpnService::class.java))
        delay(settleDelayMs)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface IntegrationServiceCleanupEntryPoint {
    fun acceptedUserStopRecorder(): AcceptedUserStopRecorder

    fun serviceIntentArbiter(): ServiceIntentArbiter

    fun serviceStateStore(): ServiceStateStore
}
