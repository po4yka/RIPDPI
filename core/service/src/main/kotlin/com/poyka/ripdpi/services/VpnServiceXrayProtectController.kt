package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.core.XrayProtectController
import com.poyka.ripdpi.data.FailureReason

/**
 * Production [XrayProtectController] for the embedded Xray provider.
 *
 * ### Direct-JNI protect transport (not UDS+SCM_RIGHTS)
 * libXray protects every outbound socket through its JNI `DialerController.protectFd`
 * callback, which `:core:engine` (`XrayNativeBridgeLibXrayImpl`) routes to
 * [XrayProtectController.protect]. So unlike the native tun2socks data plane —
 * which sends fds over the Unix domain socket in [VpnProtectSocketServer] — the
 * Xray path uses the "Direct JNI callback" variant of
 * `.claude/rules/vpnservice-protect-invariant.md`: this controller wraps
 * `android.net.VpnService.protect(int)` DIRECTLY.
 *
 * ### Fail-closed + monitor parity
 * - A `false` return or a thrown exception from `protect` is reported through the
 *   SAME [VpnProtectFailureMonitor] the native [ProtectSocketFdProtector] uses,
 *   so a denial surfaces downstream as
 *   [com.poyka.ripdpi.data.xray.XrayProviderFailureClass.ProtectFailure] (the
 *   monitor event carries [FailureReason.PermissionLost] / [FailureReason.NativeError]).
 * - The controller NEVER returns `true` on a throw — libXray closes the socket
 *   and fails the connection rather than leaking an unprotected socket into the
 *   TUN route (the packet-loop hazard the invariant exists to prevent).
 *
 * Only loopback `127.0.0.1` / `[::1]` sockets may legitimately go unprotected;
 * libXray's local SOCKS inbound binds loopback and does not flow through this
 * callback, so every fd reaching here is an outbound that MUST be protected.
 */
internal class VpnServiceXrayProtectController(
    private val fdProtector: (Int) -> Boolean,
    private val protectFailureMonitor: VpnProtectFailureMonitor,
    private val clock: () -> Long = System::currentTimeMillis,
) : XrayProtectController {
    private companion object {
        private val log = Logger.withTag("XrayProtect")
    }

    /**
     * Last protect-failure detail (raw; redact before surfacing). Read by
     * [XrayProviderSnapshotDeriver] to classify a ProtectFailure. Cleared by
     * [clearLastFailure] at the start of a new session.
     */
    @Volatile
    var lastFailureDetail: String? = null
        private set

    @Volatile
    var providerGenerationProvider: () -> Long? = { null }

    /** Reset the recorded failure detail at the start of a fresh session. */
    fun clearLastFailure() {
        lastFailureDetail = null
    }

    @Suppress("TooGenericExceptionCaught")
    override fun protect(fd: Int): Boolean =
        try {
            val protected = fdProtector(fd)
            if (!protected) {
                reportFailure(
                    fd = fd,
                    reason = FailureReason.PermissionLost("VPN"),
                    detail = "VpnService.protect() returned false for xray outbound fd=$fd",
                )
            } else {
                log.d { "protected xray outbound fd=$fd" }
            }
            protected
        } catch (error: SecurityException) {
            reportFailure(
                fd = fd,
                reason = FailureReason.PermissionLost("VPN"),
                detail = error.message ?: "VpnService.protect() threw SecurityException",
            )
            // Fail-closed: never proceed unprotected.
            false
        } catch (error: Exception) {
            reportFailure(
                fd = fd,
                reason =
                    FailureReason.NativeError(
                        "VpnService.protect() failed for xray fd=$fd: ${error.message ?: "unknown error"}",
                    ),
                detail = error.message ?: "VpnService.protect() threw ${error::class.java.simpleName}",
            )
            false
        }

    private fun reportFailure(
        fd: Int,
        reason: FailureReason,
        detail: String,
    ) {
        lastFailureDetail = detail
        protectFailureMonitor.report(
            VpnProtectFailureEvent(
                fd = fd,
                reason = reason,
                detail = detail,
                detectedAt = clock(),
                providerGeneration = providerGenerationProvider(),
            ),
        )
        log.e { "xray protect failed for fd=$fd: $detail" }
    }
}
