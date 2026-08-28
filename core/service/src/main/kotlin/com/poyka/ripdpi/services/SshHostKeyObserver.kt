package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.SshHostKeyProbeResult

/** Observes a key without credentials; callers must explicitly confirm trust before authentication. */
interface SshHostKeyObserver {
    /** Cancellation revokes the observation; a still-running DNS/JNI call retains its operation slot. */
    suspend fun observe(
        server: String,
        port: Int,
    ): SshHostKeyProbeResult
}
