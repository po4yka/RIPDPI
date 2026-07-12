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
    private val activeProtectSocketPathProvider: ActiveProtectSocketPathProvider,
) {
    private var sessionComponent: VpnServiceSessionComponent? = null
    private var coordinator: VpnServiceRuntimeCoordinator? = null
    private var protectSocketServer: VpnProtectSocketServer? = null
    private val cleanup = VpnServiceSessionCleanup()

    fun createShellDelegate(): ServiceShellDelegate {
        val entryPoint = createSessionEntryPoint()
        val runtimeCoordinator = entryPoint.coordinator()
        val socketServer = entryPoint.protectSocketServer()
        coordinator = runtimeCoordinator
        protectSocketServer = socketServer
        advertiseProtectPath(
            startProtectSocketServer = socketServer::start,
            advertiseProtectPath = { activeProtectSocketPathProvider.set(socketServer.socketPath) },
        )
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
                cleanup.revokeSession(
                    stopRuntime = runtimeCoordinator::stop,
                    destroyCoordinator = runtimeCoordinator::onDestroy,
                    cleanupSocketProtection = ::cleanupNativeProtect,
                )
            },
        )
    }

    fun destroy() {
        cleanup.destroySession(
            destroyCoordinator = { coordinator?.onDestroy() },
            cleanupSocketProtection = ::cleanupNativeProtect,
        )
        protectSocketServer = null
        coordinator = null
        sessionComponent = null
    }

    private fun cleanupNativeProtect() {
        withdrawProtectPath(
            withdrawProtectPath = activeProtectSocketPathProvider::clear,
            cleanupNativeProtect = {
                cleanup.cleanupNativeProtect(
                    unregisterNativeProtect = VpnNativeProtectRegistration::unregister,
                    stopProtectSocketServer = { protectSocketServer?.stop() },
                )
            },
        )
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

/**
 * Protect-path advertise ordering seam (session start).
 *
 * The protect socket server MUST be listening before the env path is advertised:
 * a relay helper that reads [ActiveProtectSocketPathProvider.current] fails closed
 * if the path is set but no server answers. So [startProtectSocketServer] runs
 * strictly before [advertiseProtectPath].
 *
 * Extracted only to pin this ordering in a unit test ([VpnServiceSessionLifecycleTest]);
 * the runtime effect is identical to the inline `start(); set(path)` it replaced.
 */
internal inline fun advertiseProtectPath(
    startProtectSocketServer: () -> Unit,
    advertiseProtectPath: () -> Unit,
) {
    startProtectSocketServer()
    advertiseProtectPath()
}

/**
 * Protect-path withdraw ordering seam (session teardown / revoke / destroy).
 *
 * The env path MUST be withdrawn before the protect socket server stops, so a
 * relay helper never reads a stale path pointing at a dead UDS. So
 * [withdrawProtectPath] runs strictly before [cleanupNativeProtect] (which stops
 * the server).
 *
 * Extracted only to pin this ordering in a unit test ([VpnServiceSessionLifecycleTest]);
 * the runtime effect is identical to the inline `clear(); cleanupNativeProtect(...)`
 * it replaced.
 */
internal inline fun withdrawProtectPath(
    withdrawProtectPath: () -> Unit,
    cleanupNativeProtect: () -> Unit,
) {
    withdrawProtectPath()
    cleanupNativeProtect()
}
