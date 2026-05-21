package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings

/**
 * Drives the JNI `VpnService.protect` callback registration for both native
 * libraries.
 *
 * Each `jniRegisterVpnProtect` returns a generation token. This object keeps
 * the proxy and WARP tokens between [register] and [unregister] and passes
 * them back, so a stale unregister from a superseded VPN session cannot clear
 * a newer session's callback. See `docs/architecture/JNI_CONTRACT.md` §8.
 */
internal object VpnNativeProtectRegistration {
    /** Token from the last proxy registration; `0` means no live registration. */
    @Volatile
    private var proxyToken: Long = 0L

    /** Token from the last WARP registration; `0` means no live registration. */
    @Volatile
    private var warpToken: Long = 0L

    fun register(service: VpnService) {
        proxyToken = RipDpiProxyNativeBindings.jniRegisterVpnProtect(service)
        warpToken = RipDpiWarpNativeBindings.jniRegisterVpnProtect(service)
    }

    fun unregister() {
        RipDpiProxyNativeBindings.jniUnregisterVpnProtect(proxyToken)
        RipDpiWarpNativeBindings.jniUnregisterVpnProtect(warpToken)
        proxyToken = 0L
        warpToken = 0L
    }
}
