package com.poyka.ripdpi.services

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-singleton holder for the currently-active `VpnService.protect` UDS path.
 *
 * Set while a VPN session's [VpnProtectSocketServer] is listening and cleared
 * when it stops (see [VpnServiceSessionLifecycle]). The subprocess relay
 * spawners read [current] to decide whether to advertise [ENV_VAR] to a helper
 * binary (`ripdpi-naiveproxy`, `ripdpi-webtunnel`, `ripdpi-cloudflare-origin`):
 * those helpers protect their outbound sockets via the UDS at that path and
 * **fail closed** if the path is set but no server answers. So the path MUST be
 * advertised ONLY while the protect server is up — never in proxy-only mode or
 * when the VPN is down, where it would break the helper's connect.
 *
 * `@Singleton` is required because the consumers (the relay managers) are
 * app-singletons that outlive the per-session protect server.
 */
@Singleton
class ActiveProtectSocketPathProvider
    @Inject
    constructor() {
        private class Protection(
            val path: String,
            val protect: (Int) -> Boolean,
        )

        private val owner = AtomicReference<Protection?>(null)

        /** Advertise `socketPath` as the active protect UDS (VPN session started). */
        fun set(
            socketPath: String,
            protect: (Int) -> Boolean,
        ) {
            owner.set(Protection(socketPath, protect))
        }

        /** The captured owner fails closed when a later VPN session replaces or withdraws it. */
        fun captureDirectProtection(): com.poyka.ripdpi.core.XrayProtectController? {
            val captured = owner.get() ?: return null
            return com.poyka.ripdpi.core.XrayProtectController { fd ->
                owner.get() === captured && captured.protect(fd) && owner.get() === captured
            }
        }

        /** Stop advertising any path (VPN session stopped / revoked). */
        fun clear() {
            owner.set(null)
        }

        /** The active protect UDS path, or `null` when no VPN session is listening. */
        fun current(): String? = owner.get()?.path

        companion object {
            /** Environment variable read by the subprocess-protect helper crate. */
            const val ENV_VAR: String = "RIPDPI_PROTECT_PATH"
        }
    }
