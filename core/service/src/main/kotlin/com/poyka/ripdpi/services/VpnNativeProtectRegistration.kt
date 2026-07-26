package com.poyka.ripdpi.services

import android.net.VpnService
import com.poyka.ripdpi.core.RipDpiAmneziaWgNativeBindings
import com.poyka.ripdpi.core.RipDpiProxyNativeBindings
import com.poyka.ripdpi.core.RipDpiRelayNativeBindings
import com.poyka.ripdpi.core.RipDpiWarpNativeBindings
import com.poyka.ripdpi.core.Tun2SocksNativeBindings

/**
 * Drives the JNI `VpnService.protect` callback registration for native libraries
 * that open upstream sockets: proxy, relay, WARP, AmneziaWG, and tunnel direct DNS.
 *
 * Each `jniRegisterVpnProtect` returns a generation token. This object keeps
 * the proxy, relay, WARP, AWG, and direct-DNS tokens between [register] and [unregister] and passes
 * them back, so a stale unregister from a superseded VPN session cannot clear
 * a newer session's callback. See `docs/architecture/JNI_CONTRACT.md` §8.
 *
 * Each `.so` owns its own protect slot — registering proxy does not cover relay,
 * WARP, AWG, or tunnel direct-DNS sockets. Register all slots before any native runtime is created
 * or started. See `.claude/rules/vpnservice-protect-invariant.md`.
 */
internal object VpnNativeProtectRegistration {
    // All access serialized by @Synchronized; tokens must be registered/unregistered as a group.

    private var proxyToken: Long = 0L
    private var relayToken: Long = 0L
    private var warpToken: Long = 0L
    private var awgToken: Long = 0L
    private var directDnsToken: Long = 0L

    /**
     * Replaceable in tests (same package) to stub out JNI.
     * Production code leaves these at the defaults pointing to the real statics.
     */
    internal var proxyRegister: (VpnService) -> Long = { RipDpiProxyNativeBindings.jniRegisterVpnProtect(it) }
    internal var relayRegister: (VpnService) -> Long = { RipDpiRelayNativeBindings.jniRegisterVpnProtect(it) }
    internal var warpRegister: (VpnService) -> Long = { RipDpiWarpNativeBindings.jniRegisterVpnProtect(it) }
    internal var awgRegister: (VpnService) -> Long = { RipDpiAmneziaWgNativeBindings.jniRegisterVpnProtect(it) }
    internal var directDnsRegister: (Any) -> Long = Tun2SocksNativeBindings::registerDirectDnsSocketBinder
    internal var proxyUnregister: (Long) -> Unit = { RipDpiProxyNativeBindings.jniUnregisterVpnProtect(it) }
    internal var relayUnregister: (Long) -> Unit = { RipDpiRelayNativeBindings.jniUnregisterVpnProtect(it) }
    internal var warpUnregister: (Long) -> Unit = { RipDpiWarpNativeBindings.jniUnregisterVpnProtect(it) }
    internal var awgUnregister: (Long) -> Unit = { RipDpiAmneziaWgNativeBindings.jniUnregisterVpnProtect(it) }
    internal var directDnsUnregister: (Long) -> Unit = Tun2SocksNativeBindings::unregisterDirectDnsSocketBinder

    @Synchronized
    fun register(
        service: VpnService,
        directDnsBridge: Any,
    ) {
        if ((proxyToken or relayToken or warpToken or awgToken or directDnsToken) != 0L) {
            unregister()
        }
        val registrationFailure =
            runCatching {
                proxyToken = registerToken("proxy") { proxyRegister(service) }
                relayToken = registerToken("relay") { relayRegister(service) }
                warpToken = registerToken("WARP") { warpRegister(service) }
                awgToken = registerToken("AmneziaWG") { awgRegister(service) }
                directDnsToken = registerToken("tunnel direct DNS") { directDnsRegister(directDnsBridge) }
            }.exceptionOrNull()
        if (registrationFailure != null) {
            runCatching { unregister() }
                .exceptionOrNull()
                ?.let(registrationFailure::addSuppressed)
            throw registrationFailure
        }
    }

    @Synchronized
    fun unregister() {
        var failure: Throwable? = null

        fun release(
            token: Long,
            unregister: (Long) -> Unit,
            clear: () -> Unit,
        ) {
            if (token == 0L) return
            runCatching { unregister(token) }
                .onSuccess { clear() }
                .onFailure { current ->
                    failure?.addSuppressed(current) ?: run { failure = current }
                }
        }

        release(proxyToken, proxyUnregister) { proxyToken = 0L }
        release(relayToken, relayUnregister) { relayToken = 0L }
        release(warpToken, warpUnregister) { warpToken = 0L }
        release(awgToken, awgUnregister) { awgToken = 0L }
        release(directDnsToken, directDnsUnregister) { directDnsToken = 0L }
        failure?.let { throw it }
    }

    private inline fun registerToken(
        owner: String,
        register: () -> Long,
    ): Long = register().also { token -> check(token != 0L) { "$owner VPN protect registration failed" } }
}
