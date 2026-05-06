package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.service.runtime.vpn.VpnServiceRuntimeCoordinator
import dagger.hilt.EntryPoints
import javax.inject.Provider

internal class VpnServiceSessionLifecycle(
    private val service: RipDpiVpnService,
    private val serviceStateStore: ServiceStateStore,
    private val sessionComponentBuilderProvider: Provider<VpnServiceSessionComponentBuilder>,
) {
    private var sessionComponent: VpnServiceSessionComponent? = null
    private var coordinator: VpnServiceRuntimeCoordinator? = null
    private var protectSocketServer: VpnProtectSocketServer? = null

    fun createShellDelegate(): ServiceShellDelegate {
        val entryPoint = createSessionEntryPoint()
        val runtimeCoordinator = entryPoint.coordinator()
        val socketServer = entryPoint.protectSocketServer()
        coordinator = runtimeCoordinator
        protectSocketServer = socketServer
        socketServer.start()
        VpnNativeProtectRegistration.register(service)
        return ServiceShellDelegate(
            serviceScope = service.serviceScope,
            serviceLabel = "vpn",
            onStart = runtimeCoordinator::start,
            onStop = runtimeCoordinator::stop,
            onRevoke = {
                serviceStateStore.emitFailed(
                    sender = Sender.VPN,
                    reason = FailureReason.PermissionLost("VPN"),
                )
                runtimeCoordinator.stop()
            },
        )
    }

    fun destroy(revoked: Boolean) {
        VpnNativeProtectRegistration.unregister()
        protectSocketServer?.stop()
        if (!revoked) {
            coordinator?.onDestroy()
        }
        protectSocketServer = null
        coordinator = null
        sessionComponent = null
    }

    private fun createSessionEntryPoint(): VpnServiceSessionEntryPoint {
        sessionComponent =
            sessionComponentBuilderProvider
                .get()
                .host(service)
                .vpnService(service)
                .build()
        return EntryPoints.get(checkNotNull(sessionComponent), VpnServiceSessionEntryPoint::class.java)
    }
}
