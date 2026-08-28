package com.poyka.ripdpi.core

data class SshHostKeyProbeRequest(
    val addressLiteral: String,
    val port: Int,
    val timeoutMillis: Int = 5_000,
)

sealed interface SshHostKeyProbeResult {
    data class Observed(
        val fingerprintSha256: String,
        val algorithm: String,
    ) : SshHostKeyProbeResult

    data class Failed(
        val reason: SshHostKeyProbeFailure,
    ) : SshHostKeyProbeResult
}

enum class SshHostKeyProbeFailure {
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

/** Called by JNI before connect; the owner must protect and bind the socket or deny it. */
fun interface SshProbeSocketController {
    fun protectSocket(fd: Int): Boolean
}
