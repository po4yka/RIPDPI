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
        private val path = AtomicReference<String?>(null)

        /** Advertise `socketPath` as the active protect UDS (VPN session started). */
        fun set(socketPath: String) {
            path.set(socketPath)
        }

        /** Stop advertising any path (VPN session stopped / revoked). */
        fun clear() {
            path.set(null)
        }

        /** The active protect UDS path, or `null` when no VPN session is listening. */
        fun current(): String? = path.get()

        companion object {
            /** Environment variable read by the subprocess-protect helper crate. */
            const val ENV_VAR: String = "RIPDPI_PROTECT_PATH"
        }
    }
