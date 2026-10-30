package com.poyka.ripdpi.services

/** Observes a key without credentials; callers must explicitly confirm trust before authentication. */
interface SshHostKeyObserver {
    /** Cancellation revokes the observation; a still-running DNS/JNI call retains its operation slot. */
    suspend fun observe(
        server: String,
        port: Int,
    ): SshHostKeyObservation
}

sealed interface SshHostKeyObservation {
    data class Observed(
        val fingerprintSha256: String,
        val algorithm: String,
    ) : SshHostKeyObservation

    data class Failed(
        val reason: SshHostKeyObservationFailure,
    ) : SshHostKeyObservation
}

enum class SshHostKeyObservationFailure {
    InvalidInput,
    Timeout,
    ConnectFailed,
    HandshakeFailed,
    ProtectionDenied,
    InternalFailure,
    Busy,
    NoUnderlay,
    NetworkChanged,
}
