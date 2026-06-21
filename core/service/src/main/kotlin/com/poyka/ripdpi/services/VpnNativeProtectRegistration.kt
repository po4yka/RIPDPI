package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.core.RipDpiAmneziaWgNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiRelayNativeBindings
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings

/**
 * Drives the JNI `VpnService.protect` callback registration for native libraries
 * that open upstream sockets: proxy, relay, WARP, and AmneziaWG.
 *
 * Each `jniRegisterVpnProtect` returns a generation token. This object keeps
 * the proxy, relay, WARP, and AWG tokens between [register] and [unregister] and passes
 * them back, so a stale unregister from a superseded VPN session cannot clear
 * a newer session's callback. See `docs/architecture/JNI_CONTRACT.md` §8.
 *
 * Each `.so` owns its own protect slot — registering proxy does not cover relay,
 * WARP, or AWG sockets. Register all slots before any native runtime is created
 * or started. See `.claude/rules/vpnservice-protect-invariant.md`.
 */
internal object VpnNativeProtectRegistration {
    // All access serialized by @Synchronized; tokens must be registered/unregistered as a group.

    private var proxyToken: Long = 0L
    private var relayToken: Long = 0L
    private var warpToken: Long = 0L
    private var awgToken: Long = 0L

    /**
     * Replaceable in tests (same package) to stub out JNI.
     * Production code leaves these at the defaults pointing to the real statics.
     */
    internal var proxyRegister: (VpnService) -> Long = { RipDpiProxyNativeBindings.jniRegisterVpnProtect(it) }
    internal var relayRegister: (VpnService) -> Long = { RipDpiRelayNativeBindings.jniRegisterVpnProtect(it) }
    internal var warpRegister: (VpnService) -> Long = { RipDpiWarpNativeBindings.jniRegisterVpnProtect(it) }
    internal var awgRegister: (VpnService) -> Long = { RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect(it) }
    internal var proxyUnregister: (Long) -> Unit = { RipDpiProxyNativeBindings.jniUnregisterVpnProtect(it) }
    internal var relayUnregister: (Long) -> Unit = { RipDpiRelayNativeBindings.jniUnregisterVpnProtect(it) }
    internal var warpUnregister: (Long) -> Unit = { RipDpiWarpNativeBindings.jniUnregisterVpnProtect(it) }
    internal var awgUnregister: (Long) -> Unit = { RipDpiAmneziaWgNativeBindings.jniUnregisterVpnProtect(it) }

    @Synchronized
    fun register(service: VpnService) {
        if (proxyToken != 0L || relayToken != 0L || warpToken != 0L || awgToken != 0L) {
            // Double-registration guard: unregister stale tokens before registering anew.
            proxyUnregister(proxyToken)
            relayUnregister(relayToken)
            warpUnregister(warpToken)
            awgUnregister(awgToken)
            proxyToken = 0L
            relayToken = 0L
            warpToken = 0L
            awgToken = 0L
        }
        proxyToken = proxyRegister(service)
        relayToken = relayRegister(service)
        warpToken = warpRegister(service)
        awgToken = awgRegister(service)
    }

    @Synchronized
    fun unregister() {
        proxyUnregister(proxyToken)
        relayUnregister(relayToken)
        warpUnregister(warpToken)
        awgUnregister(awgToken)
        proxyToken = 0L
        relayToken = 0L
        warpToken = 0L
        awgToken = 0L
    }
}
