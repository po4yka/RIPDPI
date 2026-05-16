package com.poyka.ripdpi.services.lifecycle

/**
 * Guards TUN establishment: every required transport socket must be successfully
 * protected before the TUN fd may be opened.
 *
 * Inject a [SocketProtector] that delegates to [android.net.VpnService.protect] in
 * production and to a recording fake in tests.
 */
class ProtectGate(
    private val protector: SocketProtector,
) {
    /**
     * Returns true when [protector] succeeds for the given [socketFd], false otherwise.
     *
     * A false result means the caller MUST abort TUN establishment and transition to
     * [VpnLifecycleState.Blocked] with reason [BlockedReason.ProtectFailed].
     */
    fun canProtect(socketFd: Int): Boolean = protector.protect(socketFd)
}

/** Abstraction over [android.net.VpnService.protect] — use a fake in unit tests. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}
