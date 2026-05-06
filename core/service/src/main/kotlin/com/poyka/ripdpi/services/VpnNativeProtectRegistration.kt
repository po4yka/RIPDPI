package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings

internal object VpnNativeProtectRegistration {
    fun register(service: VpnService) {
        RipDpiProxyNativeBindings.jniRegisterVpnProtect(service)
        RipDpiWarpNativeBindings.jniRegisterVpnProtect(service)
    }

    fun unregister() {
        RipDpiProxyNativeBindings.jniUnregisterVpnProtect()
        RipDpiWarpNativeBindings.jniUnregisterVpnProtect()
    }
}
