package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RelaySocketProtection
import com.poyka.ripdpi.core.WarpProbeSocketController
import java.util.concurrent.atomic.AtomicBoolean
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
        class Lease internal constructor(
            internal val path: String,
            internal val protect: (Int) -> Boolean,
        ) {
            internal val probeAdmissionOpen = AtomicBoolean(true)
        }

        private val owner = AtomicReference<Lease?>(null)
        private val probeAdmission = Any()
        private var inactiveProbes = 0

        internal class WarpProbeLease(
            val socketProtection: RelaySocketProtection,
            val controller: WarpProbeSocketController,
            private val current: () -> Boolean,
            private val release: () -> Unit,
        ) : java.io.Closeable {
            override fun close() = release()

            fun isCurrent(): Boolean = current()
        }

        /** Admission is held for the whole blocking probe, including caller cancellation. */
        internal fun captureWarpProbe(): WarpProbeLease =
            synchronized(probeAdmission) {
                val captured = owner.get()
                if (captured == null) inactiveProbes++
                val closed = AtomicBoolean(false)

                fun current(): Boolean =
                    !closed.get() && owner.get() === captured && (captured?.probeAdmissionOpen?.get() ?: true)
                val controller =
                    WarpProbeSocketController { fd ->
                        current() && (captured == null || captured.protect(fd)) && current()
                    }
                WarpProbeLease(
                    if (captured == null) RelaySocketProtection.Inactive else RelaySocketProtection.VpnRequired,
                    controller,
                    ::current,
                ) {
                    if (closed.compareAndSet(false, true) && captured == null) {
                        synchronized(probeAdmission) { inactiveProbes-- }
                    }
                }
            }

        /** Retain the owner during cleanup, but reject new and captured probes. */
        internal fun closeProbeAdmission(lease: Lease) {
            lease.probeAdmissionOpen.set(false)
        }

        /** Advertise `socketPath` as the active protect UDS (VPN session started). */
        fun set(
            socketPath: String,
            protect: (Int) -> Boolean,
        ): Lease =
            synchronized(probeAdmission) {
                check(inactiveProbes == 0) { "WARP endpoint check is still running; retry VPN startup" }
                Lease(socketPath, protect).also(owner::set)
            }

        /** The captured owner fails closed when a later VPN session replaces or withdraws it. */
        fun captureDirectProtection(): com.poyka.ripdpi.core.XrayProtectController? {
            val captured = owner.get() ?: return null
            return com.poyka.ripdpi.core.XrayProtectController { fd ->
                owner.get() === captured && captured.protect(fd) && owner.get() === captured
            }
        }

        /** Withdraw only the path advertised by this session. */
        fun clear(lease: Lease) {
            synchronized(probeAdmission) { owner.compareAndSet(lease, null) }
        }

        /** The active protect UDS path, or `null` when no VPN session is listening. */
        fun current(): String? = owner.get()?.path

        companion object {
            /** Environment variable read by the subprocess-protect helper crate. */
            const val ENV_VAR: String = "RIPDPI_PROTECT_PATH"
        }
    }
